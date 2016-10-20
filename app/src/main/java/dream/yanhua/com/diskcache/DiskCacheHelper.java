package dream.yanhua.com.diskcache;

import android.text.TextUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

import dream.yanhua.com.diskcache.impl.LruDiskCache;
import dream.yanhua.com.diskcache.naming.Md5Sha1FileNameGenerator;
import dream.yanhua.com.diskcache.utils.IoUtils;

class DiskCacheHelper {
    private static final boolean DEBUG = false;
    /**
     * 200M
     */
    private static final long CACHE_MAX_SIZE = 50 * 1024 * 1024;
    private static final int CACHE_MAX_COUNT = 1000;

    private LruDiskCache mDiskLruCache;

    private DiskCacheHelper(File cacheDir) {
        try {
            mDiskLruCache = new LruDiskCache(cacheDir, null, new Md5Sha1FileNameGenerator(), CACHE_MAX_SIZE, CACHE_MAX_COUNT);
        } catch (Exception e) {
            if (DEBUG) {
                e.printStackTrace();
            }
        }
    }

    public synchronized static DiskCacheHelper get(File cacheDir) {
        return new DiskCacheHelper(cacheDir);
    }

    public synchronized void put(final String key, final String value) {
        putString(key, value);
    }


    private void putString(final String key, final String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (mDiskLruCache != null) {
            ByteArrayInputStream inputStream = null;
            try {
                inputStream = new ByteArrayInputStream(value.getBytes("UTF-8"));
                mDiskLruCache.save(key, inputStream);
            } catch (Exception e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
            } finally {
                IoUtils.closeSilently(inputStream);
            }
        }
    }

    public synchronized String getString(String key) {
        return get(key);
    }

    private String get(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        if (mDiskLruCache != null) {
            File file = mDiskLruCache.get(key);
            if (file != null) {
                FileInputStream fileInputStream = null;
                ByteArrayOutputStream outputStream = null;
                try {
                    fileInputStream = new FileInputStream(file);
                    outputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8 * 1024];
                    int read;
                    while ((read = fileInputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, read);
                    }
                    return outputStream.toString("UTF-8");
                } catch (Exception e) {
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                } finally {
                    IoUtils.closeSilently(fileInputStream);
                    IoUtils.closeSilently(outputStream);
                }
            }
        }
        return null;
    }

    public synchronized void clear() {
        new Thread() {
            @Override
            public void run() {
                synchronized (DiskCacheHelper.class) {
                    if (mDiskLruCache != null) {
                        try {
                            mDiskLruCache.clear();
                        } catch (Exception e) {
                            if (DEBUG) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }.start();
    }
}
