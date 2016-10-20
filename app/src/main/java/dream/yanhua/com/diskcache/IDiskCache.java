package dream.yanhua.com.diskcache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import dream.yanhua.com.diskcache.utils.IoUtils;

/**
 * Interface for disk cache
 */
public interface IDiskCache {
    File getDirectory();

    File get(String key);

    boolean save(String key, InputStream inputStream, IoUtils.CopyListener listener) throws IOException;

    boolean save(String key, InputStream inputStream) throws IOException;

    boolean remove(String key);

    void close();

    void clear();
}
