package dream.yanhua.com.diskcache.impl;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiskLruCache implements Closeable {
    static final String JOURNAL_FILE = "journal";
    static final String JOURNAL_FILE_TEMP = "journal.tmp";
    static final String JOURNAL_FILE_BACKUP = "journal.bkp";
    static final String MAGIC = "libcore.io.DiskLruCache";
    static final String VERSION_1 = "1";
    static final long ANY_SEQUENCE_NUMBER = -1;
    static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final String CLEAN = "CLEAN";
    private static final String DIRTY = "DIRTY";
    private static final String REMOVE = "REMOVE";
    private static final String READ = "READ";

    private final File directory;
    private final File journalFile;
    private final File journalFileTmp;
    private final File journalFileBackup;
    private final int appVersion;
    private long maxSize;
    private int maxFileCount;
    private final int valueCount;
    private long size = 0;
    private int fileCount = 0;
    private Writer journalWriter;
    private final LinkedHashMap<String, Entry> lruEntries =
            new LinkedHashMap<String, Entry>(0, 0.75f, true);
    private int redundantOpCount;


    private long nextSequenceNumber = 0;

    final ThreadPoolExecutor executorService =
            new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    private final Callable<Void> cleanupCallable = new Callable<Void>() {
        public Void call() throws Exception {
            synchronized (DiskLruCache.this) {
                if (journalWriter == null) {
                    return null; // Closed.
                }
                trimToSize();
                trimToFileCount();
                if (journalRebuildRequired()) {
                    rebuildJournal();
                    redundantOpCount = 0;
                }
            }
            return null;
        }
    };

    private DiskLruCache(File directory, int appVersion, int valueCount, long maxSize, int maxFileCount) {
        this.directory = directory;
        this.appVersion = appVersion;
        this.journalFile = new File(directory, JOURNAL_FILE);
        this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
        this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
        this.valueCount = valueCount;
        this.maxSize = maxSize;
        this.maxFileCount = maxFileCount;
    }

    public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize, int maxFileCount)
            throws IOException {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (maxFileCount <= 0) {
            throw new IllegalArgumentException("maxFileCount <= 0");
        }
        if (valueCount <= 0) {
            throw new IllegalArgumentException("valueCount <= 0");
        }

        // If a bkp file exists, use it instead.
        File backupFile = new File(directory, JOURNAL_FILE_BACKUP);
        if (backupFile.exists()) {
            File journalFile = new File(directory, JOURNAL_FILE);
            // If journal file also exists just delete backup file.
            if (journalFile.exists()) {
                backupFile.delete();
            } else {
                renameTo(backupFile, journalFile, false);
            }
        }

        // Prefer to pick up where we left off.
        DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize, maxFileCount);
        if (cache.journalFile.exists()) {
            try {
                cache.readJournal();
                cache.processJournal();
                cache.journalWriter = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(cache.journalFile, true), Util.US_ASCII));
                return cache;
            } catch (IOException journalIsCorrupt) {
                System.out
                        .println("DiskLruCache "
                                + directory
                                + " is corrupt: "
                                + journalIsCorrupt.getMessage()
                                + ", removing");
                cache.delete();
            }
        }

        // Create a new empty cache.
        directory.mkdirs();
        cache = new DiskLruCache(directory, appVersion, valueCount, maxSize, maxFileCount);
        cache.rebuildJournal();
        return cache;
    }

    private void readJournal() throws IOException {
        StrictLineReader reader = new StrictLineReader(new FileInputStream(journalFile), Util.US_ASCII);
        try {
            String magic = reader.readLine();
            String version = reader.readLine();
            String appVersionString = reader.readLine();
            String valueCountString = reader.readLine();
            String blank = reader.readLine();
            if (!MAGIC.equals(magic)
                    || !VERSION_1.equals(version)
                    || !Integer.toString(appVersion).equals(appVersionString)
                    || !Integer.toString(valueCount).equals(valueCountString)
                    || !"".equals(blank)) {
                throw new IOException("unexpected journal header: [" + magic + ", " + version + ", "
                        + valueCountString + ", " + blank + "]");
            }

            int lineCount = 0;
            while (true) {
                try {
                    readJournalLine(reader.readLine());
                    lineCount++;
                } catch (EOFException endOfJournal) {
                    break;
                }
            }
            redundantOpCount = lineCount - lruEntries.size();
        } finally {
            Util.closeQuietly(reader);
        }
    }

    private void readJournalLine(String line) throws IOException {
        int firstSpace = line.indexOf(' ');
        if (firstSpace == -1) {
            throw new IOException("unexpected journal line: " + line);
        }

        int keyBegin = firstSpace + 1;
        int secondSpace = line.indexOf(' ', keyBegin);
        final String key;
        if (secondSpace == -1) {
            key = line.substring(keyBegin);
            if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
                lruEntries.remove(key);
                return;
            }
        } else {
            key = line.substring(keyBegin, secondSpace);
        }

        Entry entry = lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        }

        if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
            String[] parts = line.substring(secondSpace + 1).split(" ");
            entry.readable = true;
            entry.currentEditor = null;
            entry.setLengths(parts);
        } else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
            entry.currentEditor = new Editor(entry);
        } else if (secondSpace == -1 && firstSpace == READ.length() && line.startsWith(READ)) {
            // This work was already done by calling lruEntries.get().
        } else {
            throw new IOException("unexpected journal line: " + line);
        }
    }

    private void processJournal() throws IOException {
        deleteIfExists(journalFileTmp);
        for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
            Entry entry = i.next();
            if (entry.currentEditor == null) {
                for (int t = 0; t < valueCount; t++) {
                    size += entry.lengths[t];
                    fileCount++;
                }
            } else {
                entry.currentEditor = null;
                for (int t = 0; t < valueCount; t++) {
                    deleteIfExists(entry.getCleanFile(t));
                    deleteIfExists(entry.getDirtyFile(t));
                }
                i.remove();
            }
        }
    }

    private synchronized void rebuildJournal() throws IOException {
        if (journalWriter != null) {
            journalWriter.close();
        }

        Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(journalFileTmp), Util.US_ASCII));
        try {
            writer.write(MAGIC);
            writer.write("\n");
            writer.write(VERSION_1);
            writer.write("\n");
            writer.write(Integer.toString(appVersion));
            writer.write("\n");
            writer.write(Integer.toString(valueCount));
            writer.write("\n");
            writer.write("\n");

            for (Entry entry : lruEntries.values()) {
                if (entry.currentEditor != null) {
                    writer.write(DIRTY + ' ' + entry.key + '\n');
                } else {
                    writer.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
                }
            }
        } finally {
            writer.close();
        }

        if (journalFile.exists()) {
            renameTo(journalFile, journalFileBackup, true);
        }
        renameTo(journalFileTmp, journalFile, false);
        journalFileBackup.delete();

        journalWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(journalFile, true), Util.US_ASCII));
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException();
        }
    }

    private static void renameTo(File from, File to, boolean deleteDestination) throws IOException {
        if (deleteDestination) {
            deleteIfExists(to);
        }
        if (!from.renameTo(to)) {
            throw new IOException();
        }
    }

    public synchronized Snapshot get(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            return null;
        }

        if (!entry.readable) {
            return null;
        }

        // Open all streams eagerly to guarantee that we see a single published
        // snapshot. If we opened streams lazily then the streams could come
        // from different edits.
        File[] files = new File[valueCount];
        InputStream[] ins = new InputStream[valueCount];
        try {
            File file;
            for (int i = 0; i < valueCount; i++) {
                file = entry.getCleanFile(i);
                files[i] = file;
                ins[i] = new FileInputStream(file);
            }
        } catch (FileNotFoundException e) {
            // A file must have been deleted manually!
            for (int i = 0; i < valueCount; i++) {
                if (ins[i] != null) {
                    Util.closeQuietly(ins[i]);
                } else {
                    break;
                }
            }
            return null;
        }

        redundantOpCount++;
        journalWriter.append(READ + ' ' + key + '\n');
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return new Snapshot(key, entry.sequenceNumber, files, ins, entry.lengths);
    }

    public Editor edit(String key) throws IOException {
        return edit(key, ANY_SEQUENCE_NUMBER);
    }

    private synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER && (entry == null
                || entry.sequenceNumber != expectedSequenceNumber)) {
            return null; // Snapshot is stale.
        }
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        } else if (entry.currentEditor != null) {
            return null; // Another edit is in progress.
        }

        Editor editor = new Editor(entry);
        entry.currentEditor = editor;

        // Flush the journal before creating files to prevent file leaks.
        journalWriter.write(DIRTY + ' ' + key + '\n');
        journalWriter.flush();
        return editor;
    }

    public File getDirectory() {
        return directory;
    }

    public synchronized long getMaxSize() {
        return maxSize;
    }

    public synchronized int getMaxFileCount() {
        return maxFileCount;
    }

    public synchronized void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        executorService.submit(cleanupCallable);
    }

    public synchronized long size() {
        return size;
    }

    public synchronized long fileCount() {
        return fileCount;
    }

    private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
        Entry entry = editor.entry;
        if (entry.currentEditor != editor) {
            throw new IllegalStateException();
        }

        // If this edit is creating the entry for the first time, every index must have a value.
        if (success && !entry.readable) {
            for (int i = 0; i < valueCount; i++) {
                if (!editor.written[i]) {
                    editor.abort();
                    throw new IllegalStateException("Newly created entry didn't create value for index " + i);
                }
                if (!entry.getDirtyFile(i).exists()) {
                    editor.abort();
                    return;
                }
            }
        }

        for (int i = 0; i < valueCount; i++) {
            File dirty = entry.getDirtyFile(i);
            if (success) {
                if (dirty.exists()) {
                    File clean = entry.getCleanFile(i);
                    dirty.renameTo(clean);
                    long oldLength = entry.lengths[i];
                    long newLength = clean.length();
                    entry.lengths[i] = newLength;
                    size = size - oldLength + newLength;
                    fileCount++;
                }
            } else {
                deleteIfExists(dirty);
            }
        }

        redundantOpCount++;
        entry.currentEditor = null;
        if (entry.readable | success) {
            entry.readable = true;
            journalWriter.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
            if (success) {
                entry.sequenceNumber = nextSequenceNumber++;
            }
        } else {
            lruEntries.remove(entry.key);
            journalWriter.write(REMOVE + ' ' + entry.key + '\n');
        }
        journalWriter.flush();

        if (size > maxSize || fileCount > maxFileCount || journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }
    }

    private boolean journalRebuildRequired() {
        final int redundantOpCompactThreshold = 2000;
        return redundantOpCount >= redundantOpCompactThreshold //
                && redundantOpCount >= lruEntries.size();
    }

    public synchronized boolean remove(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null || entry.currentEditor != null) {
            return false;
        }

        for (int i = 0; i < valueCount; i++) {
            File file = entry.getCleanFile(i);
            if (file.exists() && !file.delete()) {
                throw new IOException("failed to delete " + file);
            }
            size -= entry.lengths[i];
            fileCount--;
            entry.lengths[i] = 0;
        }

        redundantOpCount++;
        journalWriter.append(REMOVE + ' ' + key + '\n');
        lruEntries.remove(key);

        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return true;
    }

    public synchronized boolean isClosed() {
        return journalWriter == null;
    }

    private void checkNotClosed() {
        if (journalWriter == null) {
            throw new IllegalStateException("cache is closed");
        }
    }

    public synchronized void flush() throws IOException {
        checkNotClosed();
        trimToSize();
        trimToFileCount();
        journalWriter.flush();
    }

    public synchronized void close() throws IOException {
        if (journalWriter == null) {
            return; // Already closed.
        }
        for (Entry entry : new ArrayList<Entry>(lruEntries.values())) {
            if (entry.currentEditor != null) {
                entry.currentEditor.abort();
            }
        }
        trimToSize();
        trimToFileCount();
        journalWriter.close();
        journalWriter = null;
    }

    private void trimToSize() throws IOException {
        while (size > maxSize) {
            Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
            remove(toEvict.getKey());
        }
    }

    private void trimToFileCount() throws IOException {
        while (fileCount > maxFileCount) {
            Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
            remove(toEvict.getKey());
        }
    }

    public void delete() throws IOException {
        close();
        Util.deleteContents(directory);
    }

    private void validateKey(String key) {
        Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"");
        }
    }

    private static String inputStreamToString(InputStream in) throws IOException {
        return Util.readFully(new InputStreamReader(in, Util.UTF_8));
    }

    public final class Snapshot implements Closeable {
        private final String key;
        private final long sequenceNumber;
        private File[] files;
        private final InputStream[] ins;
        private final long[] lengths;

        private Snapshot(String key, long sequenceNumber, File[] files, InputStream[] ins, long[] lengths) {
            this.key = key;
            this.sequenceNumber = sequenceNumber;
            this.files = files;
            this.ins = ins;
            this.lengths = lengths;
        }

        public Editor edit() throws IOException {
            return DiskLruCache.this.edit(key, sequenceNumber);
        }

        public File getFile(int index) {
            return files[index];
        }

        public InputStream getInputStream(int index) {
            return ins[index];
        }

        public String getString(int index) throws IOException {
            return inputStreamToString(getInputStream(index));
        }

        public long getLength(int index) {
            return lengths[index];
        }

        public void close() {
            for (InputStream in : ins) {
                Util.closeQuietly(in);
            }
        }
    }

    private static final OutputStream NULL_OUTPUT_STREAM = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            // Eat all writes silently. Nom nom.
        }
    };

    public final class Editor {
        private final Entry entry;
        private final boolean[] written;
        private boolean hasErrors;
        private boolean committed;

        private Editor(Entry entry) {
            this.entry = entry;
            this.written = (entry.readable) ? null : new boolean[valueCount];
        }

        public InputStream newInputStream(int index) throws IOException {
            synchronized (DiskLruCache.this) {
                if (entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                if (!entry.readable) {
                    return null;
                }
                try {
                    return new FileInputStream(entry.getCleanFile(index));
                } catch (FileNotFoundException e) {
                    return null;
                }
            }
        }


        public String getString(int index) throws IOException {
            InputStream in = newInputStream(index);
            return in != null ? inputStreamToString(in) : null;
        }

        public OutputStream newOutputStream(int index) throws IOException {
            synchronized (DiskLruCache.this) {
                if (entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                if (!entry.readable) {
                    written[index] = true;
                }
                File dirtyFile = entry.getDirtyFile(index);
                FileOutputStream outputStream;
                try {
                    outputStream = new FileOutputStream(dirtyFile);
                } catch (FileNotFoundException e) {
                    // Attempt to recreate the cache directory.
                    directory.mkdirs();
                    try {
                        outputStream = new FileOutputStream(dirtyFile);
                    } catch (FileNotFoundException e2) {
                        // We are unable to recover. Silently eat the writes.
                        return NULL_OUTPUT_STREAM;
                    }
                }
                return new FaultHidingOutputStream(outputStream);
            }
        }

        public void set(int index, String value) throws IOException {
            Writer writer = null;
            try {
                writer = new OutputStreamWriter(newOutputStream(index), Util.UTF_8);
                writer.write(value);
            } finally {
                Util.closeQuietly(writer);
            }
        }

        public void commit() throws IOException {
            if (hasErrors) {
                completeEdit(this, false);
                remove(entry.key); // The previous entry is stale.
            } else {
                completeEdit(this, true);
            }
            committed = true;
        }

        public void abort() throws IOException {
            completeEdit(this, false);
        }

        public void abortUnlessCommitted() {
            if (!committed) {
                try {
                    abort();
                } catch (IOException ignored) {
                }
            }
        }

        private class FaultHidingOutputStream extends FilterOutputStream {
            private FaultHidingOutputStream(OutputStream out) {
                super(out);
            }

            @Override
            public void write(int oneByte) {
                try {
                    out.write(oneByte);
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void write(byte[] buffer, int offset, int length) {
                try {
                    out.write(buffer, offset, length);
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void close() {
                try {
                    out.close();
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void flush() {
                try {
                    out.flush();
                } catch (IOException e) {
                    hasErrors = true;
                }
            }
        }
    }

    private final class Entry {
        private final String key;

        private final long[] lengths;

        private boolean readable;

        private Editor currentEditor;

        private long sequenceNumber;

        private Entry(String key) {
            this.key = key;
            this.lengths = new long[valueCount];
        }

        public String getLengths() throws IOException {
            StringBuilder result = new StringBuilder();
            for (long size : lengths) {
                result.append(' ').append(size);
            }
            return result.toString();
        }

        private void setLengths(String[] strings) throws IOException {
            if (strings.length != valueCount) {
                throw invalidLengths(strings);
            }

            try {
                for (int i = 0; i < strings.length; i++) {
                    lengths[i] = Long.parseLong(strings[i]);
                }
            } catch (NumberFormatException e) {
                throw invalidLengths(strings);
            }
        }

        private IOException invalidLengths(String[] strings) throws IOException {
            throw new IOException("unexpected journal line: " + java.util.Arrays.toString(strings));
        }

        public File getCleanFile(int i) {
            return new File(directory, key + "." + i);
        }

        public File getDirtyFile(int i) {
            return new File(directory, key + "." + i + ".tmp");
        }
    }
}
