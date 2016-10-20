package dream.yanhua.com.diskcache.impl;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

class StrictLineReader implements Closeable {
    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    private final InputStream in;
    private final Charset charset;

    private byte[] buf;
    private int pos;
    private int end;

    public StrictLineReader(InputStream in, Charset charset) {
        this(in, 8192, charset);
    }

    public StrictLineReader(InputStream in, int capacity, Charset charset) {
        if (in == null || charset == null) {
            throw new NullPointerException();
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        if (!(charset.equals(Util.US_ASCII))) {
            throw new IllegalArgumentException("Unsupported encoding");
        }

        this.in = in;
        this.charset = charset;
        buf = new byte[capacity];
    }

    public void close() throws IOException {
        synchronized (in) {
            if (buf != null) {
                buf = null;
                in.close();
            }
        }
    }

    public String readLine() throws IOException {
        synchronized (in) {
            if (buf == null) {
                throw new IOException("LineReader is closed");
            }

            // Read more data if we are at the end of the buffered data.
            // Though it's an error to read after an exception, we will let {@code fillBuf()}
            // throw again if that happens; thus we need to handle end == -1 as well as end == pos.
            if (pos >= end) {
                fillBuf();
            }
            // Try to find LF in the buffered data and return the line if successful.
            for (int i = pos; i != end; ++i) {
                if (buf[i] == LF) {
                    int lineEnd = (i != pos && buf[i - 1] == CR) ? i - 1 : i;
                    String res = new String(buf, pos, lineEnd - pos, charset.name());
                    pos = i + 1;
                    return res;
                }
            }

            // Let's anticipate up to 80 characters on top of those already read.
            ByteArrayOutputStream out = new ByteArrayOutputStream(end - pos + 80) {
                @Override
                public String toString() {
                    int length = (count > 0 && buf[count - 1] == CR) ? count - 1 : count;
                    try {
                        return new String(buf, 0, length, charset.name());
                    } catch (UnsupportedEncodingException e) {
                        throw new AssertionError(e); // Since we control the charset this will never happen.
                    }
                }
            };

            while (true) {
                out.write(buf, pos, end - pos);
                // Mark unterminated line in case fillBuf throws EOFException or IOException.
                end = -1;
                fillBuf();
                // Try to find LF in the buffered data and return the line if successful.
                for (int i = pos; i != end; ++i) {
                    if (buf[i] == LF) {
                        if (i != pos) {
                            out.write(buf, pos, i - pos);
                        }
                        pos = i + 1;
                        return out.toString();
                    }
                }
            }
        }
    }

    private void fillBuf() throws IOException {
        int result = in.read(buf, 0, buf.length);
        if (result == -1) {
            throw new EOFException();
        }
        pos = 0;
        end = result;
    }
}

