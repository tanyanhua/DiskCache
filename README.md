DiskCache是一个性能优秀的硬盘缓存lib，使用简单。初始化、读取数据、存入数据都是毫秒级处理速度

使用示例参考DiskCacheHelper，可以自己扩展实现DiskCacheHelper

// 缓存初始化
DiskCacheHelper helper=DiskCacheHelper.get(new File("cacheDir"));

// 缓存存入数据
helper.put("key","value");

// 缓存取出数据
String value=helper.getString("key");

// 清空缓存
helper.clear();
