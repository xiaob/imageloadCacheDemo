package xiaobo.com.imagecachedemo.Cache;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by xiaobo on 2015/10/11.
 */
public class ACacheManager {
    private final AtomicLong cacheSize;
    private final AtomicInteger cacheCount;
    private final long sizeLimit;
    private final int countLimit;
    private final Map<File,Long> lastUsageDates = Collections.synchronizedMap(new HashMap<File, Long>());
    private File cacheDir;


    public ACacheManager( File cacheDir,long sizeLimit, int countLimit) {
        this.sizeLimit = sizeLimit;
        this.countLimit = countLimit;
        this.cacheDir = cacheDir;
        cacheSize = new AtomicLong();
        cacheCount = new AtomicInteger();
        calculateCacheSizeAndCacheCount();
    }

    /**
     * 计算cacheSize 和cacheCount
     */
    private void calculateCacheSizeAndCacheCount(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                int size = 0;
                int count = 0;

                File[] cachedFiles = cacheDir.listFiles();
                if(cachedFiles!=null){
                    for (File cacheFile:cachedFiles){
                        size += cacheFile.length();
                        count += 1;
                        lastUsageDates.put(cacheFile,cacheFile.lastModified());
                    }
                    cacheCount.set(count);
                    cacheSize.set(size);
                }
            }
        }).start();
    }


    /**
     * 创建文件
     * @param key
     * @return
     */
    public File newFile(String key){
        return new File(cacheDir,key.hashCode()+"");
    }

    /**
     * 吧文件放到管理器中
     * @param file
     */
    public void put(File file) {
    }
}
