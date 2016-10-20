package dream.yanhua.com.diskcache.naming;

public class HashCodeFileNameGenerator implements FileNameGenerator {
    @Override
    public String generate(String key) {
        return String.valueOf(key.hashCode());
    }
}
