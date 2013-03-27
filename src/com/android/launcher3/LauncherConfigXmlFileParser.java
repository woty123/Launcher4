package com.android.launcher3;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.app.SearchManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import com.android.launcher.R;
import com.android.internal.util.XmlUtils;
import com.android.launcher3.LauncherSettings.Favorites;

/**
 * ARCHOS STUFF
 * Allow to read an XML configuration file from the storage.
 * Strongly inspired from LauncherProvider.DatabaseHelper, but with some differences
 * LauncherProvider can't be used as-is because it is made for XML resources only, which are compiled with aapt.
 * In particular here we can't use AttributeSet and obtainStyledAttributes()
 * @author vapillon
 *
 */
public class LauncherConfigXmlFileParser
{
	private static final String TAG = "LauncherConfigXmlFileParser";
	
    private static final String TAG_FAVORITES = "favorites";
    private static final String TAG_FAVORITE = "favorite";
    private static final String TAG_FOLDER = "folder";
    private static final String TAG_APPWIDGET = "appwidget";
    private static final String TAG_CLOCK = "clock";
    private static final String TAG_SEARCH = "search";
    /**
     * The interface to the launcher, to create showrtcuts and folders.
     * Would not have been needed in case the code of this file would have been integrated to LauncherProvider.java
     * But I prefer to separate it in this new file.
     * @author vapillon
     *
     */
	interface LauncherProviderInterface {
		void deleteId(SQLiteDatabase db, long id);
		long generateNewId();
		int allocateAppWidgetId();
		long dbInsertAndCheck(SQLiteDatabase db, String table, String nullColumnHack, ContentValues values);
	}
	
	final Context mContext;
	final LauncherProviderInterface mLauncher;
	public LauncherConfigXmlFileParser(Context context, LauncherProviderInterface launcher ) {
		mContext = context;
		mLauncher = launcher;
	
	}

    /**
     * Loads the default set of favorite packages from a File.
     * This is modified copy of LauncherProvider.DatabaseHelper.loadFavorites(SQLiteDatabase db, int workspaceResourceId).
     * Would have been better in theory to factorize, but It is easier to handle as a patch this way.
     *
     * @param db The database to write the values into
     * @param workspaceResourceFile The File
     */
    public int loadFavorites(SQLiteDatabase db, File workspaceResourceFile) {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        ContentValues values = new ContentValues();

        PackageManager packageManager = mContext.getPackageManager();
        int i = 0;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput( new FileReader(workspaceResourceFile) );

            XmlUtils.beginDocument(parser, TAG_FAVORITES);

            final int depth = parser.getDepth();

            int type;
            while (((type = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                boolean added = false;
                final String name = parser.getName();

                values.clear();
                for (int n=0; n<parser.getAttributeCount(); n++) {
                	final String attribName = parser.getAttributeName(n);
                	final String attribValue = parser.getAttributeValue(n);
                	//Log.d(TAG, ""+attribName+"="+attribValue);
                	if ("launcher:screen".equals(attribName)) {
                		values.put(LauncherSettings.Favorites.SCREEN, attribValue);
                	} else if ("launcher:x".equals(attribName)) {
                		values.put(LauncherSettings.Favorites.CELLX, attribValue);
                	} else if ("launcher:y".equals(attribName)) {
                		values.put(LauncherSettings.Favorites.CELLY, attribValue);
                	}
                	
                	// always CONTAINER_DESKTOP for now (don't handle CONTAINER_HOTSEAT)
                	long container = LauncherSettings.Favorites.CONTAINER_DESKTOP;
                	values.put(LauncherSettings.Favorites.CONTAINER, container);
                }

                if (TAG_FAVORITE.equals(name)) {
                	try {
                		long id = addAppShortcut(parser, db, values, packageManager, intent);
                    	added = id >= 0;
                	} catch (Exception e) {
                		Log.d("TAG", "addAppShortcut exception "+ e);
					}
                }else if (TAG_APPWIDGET.equals(name)) {
                    added = addAppWidget(parser,db, values, packageManager);
                }else if (TAG_SEARCH.equals(name)) {
                    added = addSearchWidget(db, values);
                } else if (TAG_CLOCK.equals(name)) {
                    added = addClockWidget(db, values);
                } 
                else if (TAG_FOLDER.equals(name)) {
                    long folderId = addFolder(parser, db, values);
                    Log.d("TAG", "folderId="+folderId);
                    added = folderId >= 0;

                    ArrayList<Long> folderItems = new ArrayList<Long>();

                    int folderDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_TAG ||
                            parser.getDepth() > folderDepth) {
                        if (type != XmlPullParser.START_TAG) {
                            continue;
                        }
                        final String folder_item_name = parser.getName();

                        values.clear();
                        values.put(LauncherSettings.Favorites.CONTAINER, folderId);

                        long id=0;
                        if (TAG_FAVORITE.equals(folder_item_name) && folderId >= 0) {
                        	try {
                                id = addAppShortcut(parser, db, values, packageManager, intent);
                                added = id >= 0;
                            	} catch (Exception e) {
                            		Log.d("TAG", "addAppShortcut exception "+ e);
            					}
                            if (id >= 0) {
                                folderItems.add(id);
                            }
                        } else {
                            throw new RuntimeException("Folders can contain only shortcuts");
                        }
                    }
                    // We can only have folders with >= 2 items, so we need to remove the
                    // folder and clean up if less than 2 items were included, or some
                    // failed to add, and less than 2 were actually added
                    if (folderItems.size() < 2 && folderId >= 0) {
                        // We just delete the folder and any items that made it
                        mLauncher.deleteId(db, folderId);
                        if (folderItems.size() > 0) {
                        	mLauncher.deleteId(db, folderItems.get(0));
                        }
                        added = false;
                    }
                }
                if (added) i++;
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Got exception parsing favorites.", e);
        } catch (IOException e) {
            Log.w(TAG, "Got exception parsing favorites.", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Got exception parsing favorites.", e);
        }

        return i;
    }

    private ComponentName getSearchWidgetProvider() {
        SearchManager searchManager =
                (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        ComponentName searchComponent = searchManager.getGlobalSearchActivity();
        if (searchComponent == null) return null;
        return getProviderInPackage(searchComponent.getPackageName());
    }

    /**
     * Gets an appwidget provider from the given package. If the package contains more than
     * one appwidget provider, an arbitrary one is returned.
     */
    private ComponentName getProviderInPackage(String packageName) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
        List<AppWidgetProviderInfo> providers = appWidgetManager.getInstalledProviders();
        if (providers == null) return null;
        final int providerCount = providers.size();
        for (int i = 0; i < providerCount; i++) {
            ComponentName provider = providers.get(i).provider;
            if (provider != null && provider.getPackageName().equals(packageName)) {
                return provider;
            }
        }
        return null;
    }
    private boolean addSearchWidget(SQLiteDatabase db, ContentValues values) {
        ComponentName cn = getSearchWidgetProvider();
        return addAppWidget(db, values, cn, 4, 1);
    }
    private boolean addClockWidget(SQLiteDatabase db, ContentValues values) {
        ComponentName cn = new ComponentName("com.android.alarmclock",
                "com.android.alarmclock.AnalogAppWidgetProvider");
        return addAppWidget(db, values, cn, 2, 2);
    }
    private boolean addAppWidget(XmlPullParser parser,SQLiteDatabase db, ContentValues values, 
            PackageManager packageManager) {

    	 String packageName=null;
         String className=null;
         int spanX = 0;
         int spanY = 0;
         
         for (int n=0; n<parser.getAttributeCount(); n++) {
         	final String attribName = parser.getAttributeName(n);
         	final String attribValue = parser.getAttributeValue(n);
         	//Log.d(TAG, ""+attribName+"="+attribValue);
         	if ("launcher:packageName".equals(attribName)) {
         		packageName = attribValue;
         	} else if ("launcher:className".equals(attribName)) {
         		className = attribValue;
         	}else if("launcher:spanX".equals(attribName)){
         		spanX = Integer.valueOf(attribValue);
         	}else if("launcher:spanY".equals(attribName)){
         		spanY = Integer.valueOf(attribValue);
         	}
         }
        if (packageName == null || className == null) {
            return false;
        }

        boolean hasPackage = true;
        ComponentName cn = new ComponentName(packageName, className);
        try {
            packageManager.getReceiverInfo(cn, 0);
        } catch (Exception e) {
            String[] packages = packageManager.currentToCanonicalPackageNames(
                    new String[] { packageName });
            cn = new ComponentName(packages[0], className);
            try {
                packageManager.getReceiverInfo(cn, 0);
            } catch (Exception e1) {
                hasPackage = false;
            }
        }

        if (hasPackage) {
            return addAppWidget(db, values, cn, spanX, spanY);
        }
        
        return false;
    }
    private boolean addAppWidget(SQLiteDatabase db, ContentValues values, ComponentName cn,
            int spanX, int spanY) {
        boolean allocatedAppWidgets = false;
        final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);

        try {
            int appWidgetId =mLauncher. allocateAppWidgetId();
            
            values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPWIDGET);
            values.put(Favorites.SPANX, spanX);
            values.put(Favorites.SPANY, spanY);
            values.put(Favorites.APPWIDGET_ID, appWidgetId);
            values.put(Favorites._ID, mLauncher.generateNewId());
            values.put(Favorites.INTENT, cn.toString());
            mLauncher. dbInsertAndCheck( db, LauncherProvider.TABLE_FAVORITES, null, values);

            allocatedAppWidgets = true;
            
            appWidgetManager.bindAppWidgetId(appWidgetId, cn);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Problem allocating appWidgetId", ex);
        }
        
        return allocatedAppWidgets;
    }
    /**
     * Copied from LauncherProvider.DatabaseHelper.addAppShortcut
     */
    private long addAppShortcut(XmlPullParser parser, SQLiteDatabase db, ContentValues values, PackageManager packageManager, Intent intent) {
        long id = -1;
        ActivityInfo info;
        
        String packageName=null;
        String className=null;
        
        for (int n=0; n<parser.getAttributeCount(); n++) {
        	final String attribName = parser.getAttributeName(n);
        	final String attribValue = parser.getAttributeValue(n);
        	//Log.d(TAG, ""+attribName+"="+attribValue);
        	if ("launcher:packageName".equals(attribName)) {
        		packageName = attribValue;
        	} else if ("launcher:className".equals(attribName)) {
        		className = attribValue;
        	}
        }

        Log.d(TAG, "addAppShortcut: packageName="+packageName+" | className="+className);
        if ((packageName==null)||(className==null)) {
        	Log.e(TAG, "addAppShortcut Error: package name or class name not found");
        	return -1;
        }

        try {
            ComponentName cn;
            try {
                cn = new ComponentName(packageName, className);
                info = packageManager.getActivityInfo(cn, 0);
            } catch (PackageManager.NameNotFoundException nnfe) {
                String[] packages = packageManager.currentToCanonicalPackageNames(
                    new String[] { packageName });
                cn = new ComponentName(packages[0], className);
                info = packageManager.getActivityInfo(cn, 0);
            }
            id = mLauncher.generateNewId();
            intent.setComponent(cn);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            values.put(Favorites.INTENT, intent.toUri(0));
            values.put(Favorites.TITLE, info.loadLabel(packageManager).toString());
            values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
            values.put(Favorites.SPANX, 1);
            values.put(Favorites.SPANY, 1);
            values.put(Favorites._ID, mLauncher.generateNewId());
            if (mLauncher.dbInsertAndCheck(db, LauncherProvider.TABLE_FAVORITES, null, values) < 0) {
            	Log.e(TAG, "addAppShortcut Error: dbInsertAndCheck returned -1");
                return -1;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to add favorite: " + packageName +
                    "/" + className, e);
        }
        return id;
    }

    /**
     * Copied from LauncherProvider.DatabaseHelper.addFolder
     */
    private long addFolder(XmlPullParser parser, SQLiteDatabase db, ContentValues values) {

    	String title_intl=null;
    	String title_localized=null;
    	String title_default=mContext.getResources().getString(R.string.folder_name); // default title (no name)
       
    	// English (default case) is labeled "launcher:title"
    	final String TAG_TITLE = "launcher:title";

    	// Localized titles are labeled "launcher:title_xy" (title_fr, title_de, etc.)
    	final String TAG_TITLE_LOCALIZED =  TAG_TITLE + "_" + Locale.getDefault().getLanguage();
        
        for (int n=0; n<parser.getAttributeCount(); n++) {
        	final String attribName = parser.getAttributeName(n);
        	final String attribValue = parser.getAttributeValue(n);
        	//Log.d(TAG, "addFolder: "+attribName+"="+attribValue);
        	
        	if (TAG_TITLE.equals(attribName)) {
        		title_intl = attribValue;
        	}
        	else if (TAG_TITLE_LOCALIZED.equals(attribName)) {
        		title_localized = attribValue;
        	}
        }
        // Choose the right title depending on what is available
        String title;
        if (title_localized!=null) {
        	title = title_localized;
        } else if (title_intl!=null) {
        	title = title_intl;
        } else {
        	title = title_default;
        }

        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER);
    	values.put(Favorites.TITLE, title);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        long id = mLauncher.generateNewId();
        values.put(Favorites._ID, id);
        if (mLauncher.dbInsertAndCheck(db, LauncherProvider.TABLE_FAVORITES, null, values) <= 0) {
            return -1;
        } else {
            return id;
        }
    }
}