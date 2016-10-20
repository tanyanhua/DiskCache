package dream.yanhua.com.diskcache.naming;

import java.security.MessageDigest;

import dream.yanhua.com.diskcache.utils.L;

public class Md5Sha1FileNameGenerator implements FileNameGenerator {
    private static final int KEY_MAX_LENGTH = 60;

    @Override
    public String generate(String key) {
        try {
            return handleKey(key);
        } catch (Exception e) {
            L.e(e);
        }
        return null;
    }

    private static String handleKey(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(key.getBytes("UTF-8"));
        byte[] data1 = sha1.digest();

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(key.getBytes());
        byte[] data2 = md5.digest();

        String key1 = StringUtil.byteArrayToHexString(data1);
        String key2 = StringUtil.byteArrayToHexString(data2);
        if (key1.length() > KEY_MAX_LENGTH / 2) {
            key1 = key1.substring(0, KEY_MAX_LENGTH / 2);
        }
        if (key2.length() > KEY_MAX_LENGTH / 2) {
            key2 = key2.substring(0, KEY_MAX_LENGTH / 2);
        }
        return key1 + key2;
    }

    private static class StringUtil {
        private static final String HEX_KEYS[] = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d",
                "e", "f"};

        public static String byteArrayToHexString(byte b[]) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                builder.append(byteToHexString(b[i]));
            }
            return builder.toString();
        }

        private static String byteToHexString(byte b) {
            int n = b;
            if (n < 0)
                n += 256;
            int d1 = n / 16;
            int d2 = n % 16;
            return HEX_KEYS[d1] + HEX_KEYS[d2];
        }
    }
}
