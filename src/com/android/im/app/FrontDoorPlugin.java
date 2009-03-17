/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.im.app;

import com.android.im.plugin.ImConfigNames;
import com.android.im.plugin.ImPluginConstants;

import android.app.Service;
import android.content.Intent;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.im.IImPlugin;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Bundle;
import android.provider.Im;
import android.util.Log;
import android.text.TextUtils;
import android.database.Cursor;
import android.net.Uri;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import dalvik.system.PathClassLoader;


public class FrontDoorPlugin extends Service {
    private final static String TAG = ImApp.LOG_TAG;
    private final static boolean LOCAL_DEBUG = false;

    // database access constants for branding resource map cache table
    private final static String[] BRANDING_RESOURCE_MAP_CACHE_PROJECTION = {
        Im.BrandingResourceMapCache.PROVIDER_ID,
        Im.BrandingResourceMapCache.APP_RES_ID,
        Im.BrandingResourceMapCache.PLUGIN_RES_ID
    };
    private final static int BRANDING_RESOURCE_MAP_CACHE_PROVIDER_ID_COLUMN = 0;
    private final static int BRANDING_RESOURCE_MAP_CACHE_APP_RES_ID_COLUMN = 1;
    private final static int BRANDING_RESOURCE_MAP_CACHE_PLUGIN_RES_ID_COLUMN = 2;

    private ArrayList<String> mProviderNames;
    private HashMap<String, String> mPackageNames;
    private HashMap<String, Map<Integer, Integer>> mBrandingResources;

    @Override
    public IBinder onBind(Intent intent) {
        // temporary mappings
        HashMap<String, Long> providerNameToId = new HashMap<String, Long>();
        HashMap<Long, String> providerIdToName = new HashMap<Long, String>();
        HashMap<String, Class> classes = new HashMap<String, Class>();

        loadThirdPartyPlugins(providerNameToId, providerIdToName, classes);
        loadBrandingResources(providerNameToId, providerIdToName, classes);

        return mBinder;
    }

    private void loadThirdPartyPlugins(
            HashMap<String, Long> providerNameToId, HashMap<Long, String> providerIdToName,
            HashMap<String, Class> classes) {
        mProviderNames = new ArrayList<String>();
        mPackageNames = new HashMap<String, String>();

        PackageManager pm = getPackageManager();
        List<ResolveInfo> plugins = pm.queryIntentServices(
                new Intent(ImPluginConstants.PLUGIN_ACTION_NAME), PackageManager.GET_META_DATA);
        for (ResolveInfo info : plugins) {
            if (LOCAL_DEBUG) log("loadThirdPartyPlugins: found plugin " + info);

            ServiceInfo serviceInfo = info.serviceInfo;
            if (serviceInfo == null) {
                Log.e(TAG, "loadThirdPartyPlugins: ignore bad plugin: " + info);
                continue;
            }

            String providerName = null;
            String providerFullName = null;
            String signUpUrl = null;
            Bundle metaData = serviceInfo.metaData;
            if (metaData != null) {
                providerName = metaData.getString(ImPluginConstants.METADATA_PROVIDER_NAME);
                providerFullName =
                    metaData.getString(ImPluginConstants.METADATA_PROVIDER_FULL_NAME);
                signUpUrl = metaData.getString(ImPluginConstants.METADATA_SIGN_UP_URL);
            }
            if (TextUtils.isEmpty(providerName) || TextUtils.isEmpty(providerFullName)) {
                Log.e(TAG, "Ignore bad IM plugin: " + info + ". Lack of required meta data");
                continue;
            }

            mProviderNames.add(providerName);
            mPackageNames.put(providerName, serviceInfo.packageName);

            String className = serviceInfo.name;
            String srcPath = serviceInfo.applicationInfo.sourceDir;
            Class pluginClass = loadClass(className, srcPath);
            if (pluginClass == null) {
                Log.e(TAG, "Can not load package for plugin " + providerName);
                continue;
            }
            classes.put(providerName, pluginClass);

            Map<String, String> config = loadProviderConfigFromPlugin(pluginClass);
            if (config == null) {
                Log.e(TAG, "Can not load config for plugin " + providerName);
                continue;
            }
            config.put(ImConfigNames.PLUGIN_PATH, srcPath);
            config.put(ImConfigNames.PLUGIN_CLASS, className);

            long providerId = DatabaseUtils.updateProviderDb(getContentResolver(),
                    providerName, providerFullName, signUpUrl, config);
            providerNameToId.put(providerName, providerId);
            providerIdToName.put(providerId, providerName);
        }
    }

    private void loadBrandingResources(
            HashMap<String, Long> providerNameToId, HashMap<Long, String> providerIdToName,
            HashMap<String, Class> classes) {
        mBrandingResources = new HashMap<String, Map<Integer, Integer>>();

        // first try load from cache
        loadBrandingResourcesFromCache(providerIdToName);

        // check and load any un-cached resources
        final ArrayList<ContentValues> valuesList = new ArrayList<ContentValues>();
        for (String provider : mProviderNames) {
            long providerId = providerNameToId.get(provider);
            if (!mBrandingResources.containsKey(provider)) {
                Map<Integer, Integer> resMap = loadBrandingResource(classes.get(provider));
                if (resMap != null) {
                    mBrandingResources.put(provider, resMap);
                    for (int appResId : resMap.keySet()) {
                        int pluginResId = resMap.get(appResId);

                        ContentValues values = new ContentValues();
                        values.put(Im.BrandingResourceMapCache.PROVIDER_ID, providerId);
                        values.put(Im.BrandingResourceMapCache.APP_RES_ID, appResId);
                        values.put(Im.BrandingResourceMapCache.PLUGIN_RES_ID, pluginResId);

                        valuesList.add(values);
                    }
                    Log.d(TAG, "Plugin " + provider + " not in cache, loaded and saved");
                }
            }
        }

        // save the changes to cache
        if (valuesList.size() > 0) {
            new Thread(new Runnable() {
                public void run() {
                    getContentResolver().bulkInsert(
                            Im.BrandingResourceMapCache.CONTENT_URI,
                            valuesList.toArray(new ContentValues[]{}));
                }
            }).start();
        }
    }

    /**
     * Try loading the branding resources from the database.
     * @param providerIdToName a map between provider ID and name.
     */
    private void loadBrandingResourcesFromCache(HashMap<Long, String> providerIdToName) {
        ContentResolver cr = getContentResolver();
        Cursor c = cr.query(
                Im.BrandingResourceMapCache.CONTENT_URI, /* URI */
                BRANDING_RESOURCE_MAP_CACHE_PROJECTION,  /* projection */
                null,                                    /* where */
                null,                                    /* where args */
                null                                     /* sort */);

        if (c != null) {
            try {
                while (c.moveToNext()) {
                    long providerId = c.getLong(BRANDING_RESOURCE_MAP_CACHE_PROVIDER_ID_COLUMN);
                    String provider = providerIdToName.get(providerId);
                    if (TextUtils.isEmpty(provider)) {
                        Log.e(TAG, "Empty provider name in branding resource map cache table.");
                        continue;
                    }
                    int appResId = c.getInt(BRANDING_RESOURCE_MAP_CACHE_APP_RES_ID_COLUMN);
                    int pluginResId = c.getInt(BRANDING_RESOURCE_MAP_CACHE_PLUGIN_RES_ID_COLUMN);

                    Map<Integer, Integer> resMap = mBrandingResources.get(provider);
                    if (resMap == null) {
                        resMap = new HashMap<Integer, Integer>();
                        mBrandingResources.put(provider, resMap);
                    }

                    resMap.put(appResId, pluginResId);
                }
            } finally {
                c.close();
            }
        } else {
            Log.e(TAG, "Query of branding resource map cache table returns empty cursor"); 
        }
    }

    /**
     * Load branding resources from one plugin.
     */
    private Map<Integer, Integer> loadBrandingResource(Class cls) {
        try {
            Method m = cls.getMethod("getResourceMap");
            // TODO: this would still cause a VM verifier exception to be thrown if.
            // the landing page Android.mk and AndroidManifest.xml don't include use-library for
            // "com.android.im.plugin". This is even with getCustomClassLoader() as the parent
            // class loader.
            return (Map)m.invoke(cls.newInstance(), new Object[]{});

        } catch (IllegalAccessException e) {
            Log.e(TAG, "Failed load the plugin resource map", e);
        } catch (InstantiationException e) {
            Log.e(TAG, "Failed load the plugin resource map", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed load the plugin resource map", e);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Failed load the plugin resource map", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed load the plugin resource map", e);
        } catch (InvocationTargetException e) {
            Log.e(TAG, "Failed load the plugin resource map", e);
        }
        return null;
    }

    /**
     * Load plugin config.
     */
    private Map<String, String> loadProviderConfigFromPlugin(Class cls) {
        try {
            Method m = cls.getMethod("onBind", Intent.class);
            com.android.im.plugin.IImPlugin plugin =
                (com.android.im.plugin.IImPlugin)m.invoke(cls.newInstance(), new Object[]{null});
            return plugin.getProviderConfig();
        } catch (IllegalAccessException e) {
            Log.e(TAG, "Could not create plugin instance", e);
        } catch (InstantiationException e) {
            Log.e(TAG, "Could not create plugin instance", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not load config from the plugin", e);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Could not load config from the plugin", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Could not load config from the plugin", e);
        } catch (InvocationTargetException e) {
            Log.e(TAG, "Could not load config from the plugin", e);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not load config from the plugin", e);
        }
        return null;
    }

    private Class loadClass(String className, String srcPath) {
        PathClassLoader loader = new PathClassLoader(srcPath, getClassLoader());
        try {
            return loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Could not find plugin class", e);
        }
        return null;
    }

    private void log(String msg) {
        Log.d(TAG, "[ImFrontDoor] " + msg);
    }


    /**
     * The implementation of IImFrontDoorPlugin defined through AIDL.
     */
    private final IImPlugin.Stub mBinder = new IImPlugin.Stub() {

        /**
         * Notify the plugin the front door activity is created. This gives the plugin a chance to
         * start its own servics, etc.
         */
        public void onStart() {
        }

        /**
         * Notify the plugin the front door activity is stopping.
         */
        public void onStop() {
        }

        /**
         * Sign in to the service for the account passed in.
         */
        public void signIn(long account) {
            if (LOCAL_DEBUG) log("signIn for account " + account);

            Intent intent = new Intent();
            intent.setData(ContentUris.withAppendedId(Im.Account.CONTENT_URI, account));
            intent.setClassName("com.android.im", "com.android.im.app.SigningInActivity");

            startActivity(intent);
        }

        /**
         * Sign out of the service for the account passed in.
         */
        public void signOut(long account) {
            if (LOCAL_DEBUG) log("signOut for account " + account);
            Intent intent = new Intent();
            intent.setData(ContentUris.withAppendedId(Im.Account.CONTENT_URI, account));
            intent.setClassName("com.android.im", "com.android.im.app.SignoutActivity");

            startActivity(intent);
        }

        public String getResourcePackageNameForProvider(String providerName) {
            return mPackageNames.get(providerName);
        }

        public Map getResourceMapForProvider(String providerName) throws RemoteException {
            return mBrandingResources.get(providerName);
        }

        public List getSupportedProviders() {
            return mProviderNames;
        }
    };

}
