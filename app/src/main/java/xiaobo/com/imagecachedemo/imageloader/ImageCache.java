package xiaobo.com.imagecachedemo.imageloader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import xiaobo.com.imagecachedemo.tools.MD5Utils;

/**
 * 从内存读取数据速度是最快的，为了更大限度使用内存，这里使用了三层缓存。
 * 硬引用缓存不会轻易被回收，用来保存常用数据，不常用的转入软引用缓存，其他全部保存在外部存储位置。
 */
public class ImageCache {

	/**
	 * 软引用缓存容量
	 */
	private final int SOFT_CACHE_SIZE = 15;
	/**
	 * 硬盘缓存版本
	 */
	private final int DISKLRU_VERSON = 1;
	/**
	 * 硬盘缓存大小
	 */
	private final int DISKLRU_SIZE = 10 * 1024 * 1024;
	/**
	 * 硬盘缓存文件名称
	 */
	private final String DISKLRU_NAME = "imagecache";
	/**
	 * 最大并发下载数量
	 */
	private final int MAX_THREAD_NUM = 8;

	/**
	 * 图片缓存技术的核心类
	 */
	private LruCache<String, Bitmap> mLruCache;
	/**
	 * 软引用核心类
	 */
	private LinkedHashMap<String, SoftReference<Bitmap>> mSoftCache; // 软引用缓存

	/**
	 * 下载队列
	 */
	private Set<ImageDownloadTask> loadingQueue;
	/**
	 * 等待下载队列
	 */
	private List<ImageDownloadTask> waitingQueue;

	/**
	 * 图片硬盘缓存核心类。
	 */
	private DiskLruCache mDiskLruCache;
	/**
	 * 正在进行下载任务的imageview
	 */
	private LinkedHashMap<String, View> imageViewManager;
	/**
	 * 单例模式
	 */
	private static ImageCache manager;

	public static ImageCache getInstanse(Context context) {
		if (manager == null) {
			manager = new ImageCache(context);
		}
		return manager;
	}

	/**
	 * 私有构造函数 初始化操作
	 * 
	 * @param context
	 */
	private ImageCache(Context context) {
		int maxMemory = (int) Runtime.getRuntime().maxMemory();
		int cacheSize = maxMemory / 8; // 硬引用缓存容量，为系统分配内存的1/8
		mLruCache = new LruCache<String, Bitmap>(cacheSize) {
			@Override
			protected int sizeOf(String key, Bitmap value) {
				if (value != null)
					return value.getRowBytes() * value.getHeight();
				else
					return 0;
			}

			@Override
			protected void entryRemoved(boolean evicted, String key,
					Bitmap oldValue, Bitmap newValue) {
				if (oldValue != null)
					// 硬引用缓存容量满的时候，会根据LRU算法把最近没有被使用的图片转入此软引用缓存
					mSoftCache.put(key, new SoftReference<Bitmap>(oldValue));
			}
		};

		mSoftCache = new LinkedHashMap<String, SoftReference<Bitmap>>(
				SOFT_CACHE_SIZE, 0.75f, true) {

			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(
					Entry<String, SoftReference<Bitmap>> eldest) {
				if (size() > SOFT_CACHE_SIZE) {
					return true;
				}
				return false;
			}
		};
		try {
			// 获取图片缓存路径
			File cacheDir = getDiskCacheDir(context, DISKLRU_NAME);
			if (!cacheDir.exists()) {
				cacheDir.mkdirs();
			}
			// 创建DiskLruCache实例，初始化缓存数据
			mDiskLruCache = DiskLruCache.open(cacheDir,
					MD5Utils.getAppVersion(context), DISKLRU_VERSON, DISKLRU_SIZE);
		} catch (IOException e) {
			e.printStackTrace();
		}
		waitingQueue = new ArrayList<ImageDownloadTask>();
		loadingQueue = new HashSet<ImageDownloadTask>();
		imageViewManager = new LinkedHashMap<String, View>();
	}

	/**
	 * 加载Bitmap对象,在LruCache和soft中检查Bitmap对象，
	 * 如果Bitmap对象不在缓存中，就会开启异步线程去硬盘加载或去网络下载图片。
	 */
	public void loadImages(ImageView imageView, String imageUrl,
			boolean priority) {
		try {
			Bitmap bitmap = getBitmapFromCache(imageUrl);

			if (bitmap == null) {
				imageViewManager.put(imageUrl, imageView);
				ImageDownloadTask task = new ImageDownloadTask();
				task.setImageUrl(imageUrl);
				addTaskToQueue(task, priority);
			} else {
				if (imageView != null && bitmap != null) {
					imageView.setImageBitmap(bitmap);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 从缓存中获取图片
	 */
	private Bitmap getBitmapFromCache(String path) {
		Bitmap bitmap;
		// 先从硬引用缓存中获取
		synchronized (mLruCache) {
			bitmap = mLruCache.get(path);
			if (bitmap != null) {
				// 如果找到的话，把元素移到LinkedHashMap的最前面，从而保证在LRU算法中是最后被删除
				mLruCache.remove(path);
				mLruCache.put(path, bitmap);
				return bitmap;
			}
		}
		// 如果硬引用缓存中找不到，到软引用缓存中找
		synchronized (mSoftCache) {
			SoftReference<Bitmap> bitmapReference = mSoftCache.get(path);
			if (bitmapReference != null) {
				bitmap = bitmapReference.get();
				if (bitmap != null) {
					// 将图片移回硬缓存
					mLruCache.put(path, bitmap);
					mSoftCache.remove(path);
					return bitmap;
				} else {
					mSoftCache.remove(path);
				}
			}
		}
		return null;
	}

	/**
	 * 添加图片到缓存
	 */
	private void addBitmapToLruCache(String id, Bitmap bitmap) {
		if (bitmap != null) {
			synchronized (mLruCache) {
				mLruCache.put(id, bitmap);
			}
		}
	}

	/**
	 * 清除软存，当应用提示内存紧张时可调用
	 */
	public void clearSoftCache() {
		mSoftCache.clear();
	}

	/**
	 * 移除单个缓存
	 * 
	 * @param key
	 */
	public synchronized void removeLruCache(String key) {
		if (key != null) {
			if (mLruCache != null) {
				Bitmap bm = mLruCache.remove(key);
				if (bm != null) {
					bm.recycle();
					bm = null;
				}
			}
			if (mSoftCache != null) {
				mSoftCache.remove(key);
			}
		}
	}



	/**
	 * 将缓存记录同步到journal文件中。
	 */
	private void fluchCache() {
		if (mDiskLruCache != null) {
			try {
				mDiskLruCache.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 根据传入的uniqueName获取硬盘缓存的路径地址。
	 */
	@SuppressLint("NewApi")
	private File getDiskCacheDir(Context context, String uniqueName) {
		String cachePath;
		if (Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState())
				|| !Environment.isExternalStorageRemovable()) {
			cachePath = context.getExternalCacheDir().getPath();
		} else {
			// 内部存储
			cachePath = context.getCacheDir().getPath();
		}
		return new File(cachePath + File.separator + uniqueName);
	}

	/**
	 * 添加任务到等待队列
	 * 
	 * @param task
	 */
	private synchronized void addTaskToWaitQueue(ImageDownloadTask task,
			boolean priority) {
		if (waitingQueue == null) {
			waitingQueue = new ArrayList<ImageDownloadTask>();

		}
		if (priority)
			waitingQueue.add(0, task);
		else
			waitingQueue.add(task);
		Log.d("download", "addTaskToWaitQueue");
	}

	/**
	 * 等待队列删除任务
	 * 
	 * @param task
	 */
	private synchronized void removeTaskFromWaitQueue(ImageDownloadTask task) {
		if (waitingQueue != null) {
			waitingQueue.remove(task);
		}
		Log.d("download", "removeTaskFromWaitQueue");
	}

	/**
	 * 添加任务到下载队列
	 * 
	 * @param task
	 */
	private synchronized void addTaskToLoadQueue(ImageDownloadTask task) {
		if (loadingQueue == null) {
			loadingQueue = new HashSet<ImageDownloadTask>();
		}
		loadingQueue.add(task);
		Log.d("download", "addTaskToLoadQueue");
	}

	/**
	 * 下载队列删除任务
	 * 
	 * @param task
	 */
	private synchronized void removeTaskFromLoadQueue(ImageDownloadTask task) {
		if (loadingQueue != null) {
			loadingQueue.remove(task);
		}
		Log.d("download", "removeTaskFromLoadQueue");
	}

	/**
	 * 添加任务到下载/等待队列
	 * 
	 * @param task
	 * @param priority
	 *            优先排队权
	 */
	private synchronized void addTaskToQueue(ImageDownloadTask task,
			boolean priority) {
		if (loadingQueue.size() <= MAX_THREAD_NUM) {
			addTaskToLoadQueue(task);
			task.execute();
			Log.d("download", "addTaskToQueue addTaskToLoadQueue");
		} else {
			addTaskToWaitQueue(task, priority);
			Log.d("download", "addTaskToQueue addTaskToWaitQueue");
		}
	}

	/**
	 * 等待任务->下载任务策略
	 */
	private synchronized void waitToLoadStrategy() {
		int index = MAX_THREAD_NUM - loadingQueue.size();
		Log.d("download", "waitToLoadStrategy index = " + index);
		for (int i = 0; i < index; i++) {
			if (waitingQueue.size() > 0) {
				ImageDownloadTask task = waitingQueue.get(0);
				removeTaskFromWaitQueue(task);
				addTaskToLoadQueue(task);
				task.execute();

			} else {
				break;
			}
		}
	}

	/**
	 * 取消所有正在下载或等待下载的任务。
	 */
	public void cancelAllTasks() {
		if (loadingQueue != null) {
			for (ImageDownloadTask task : loadingQueue) {
				task.cancel(false);
			}
			loadingQueue.clear();
		}
		if (waitingQueue != null) {
			for (ImageDownloadTask task : waitingQueue) {
				task.cancel(false);
			}
			waitingQueue.clear();
		}
		if (imageViewManager != null) {
			imageViewManager.clear();
		}
	}

	/**
	 * 异步下载图片的任务。
	 * 
	 */
	class ImageDownloadTask extends AsyncTask<String, Void, Bitmap> {

		/**
		 * 图片的URL地址
		 */
		private String imageUrl;

		public void setImageUrl(String imageurl) {
			this.imageUrl = imageurl;
		}

		@Override
		protected Bitmap doInBackground(String... params) {
			// imageUrl = params[0];
			FileDescriptor fileDescriptor = null;
			FileInputStream fileInputStream = null;
			DiskLruCache.Snapshot snapShot = null;
			Log.d("tag", "imageUrl = " + imageUrl);
			try {
				// 生成图片URL对应的key
				final String key = MD5Utils.hashKeyForDisk(imageUrl);
				// 查找key对应的缓存
				snapShot = mDiskLruCache.get(key);
				if (snapShot == null) {
					// 如果没有找到对应的缓存，则准备从网络上请求数据，并写入缓存
					DiskLruCache.Editor editor = mDiskLruCache.edit(key);
					if (editor != null) {
						OutputStream outputStream = editor.newOutputStream(0);
						if (downloadUrlToStream(imageUrl, outputStream)) {
							editor.commit();
						} else {
							editor.abort();
						}
					}
					// 缓存被写入后，再次查找key对应的缓存
					snapShot = mDiskLruCache.get(key);
				}
				if (snapShot != null) {
					fileInputStream = (FileInputStream) snapShot
							.getInputStream(0);
					fileDescriptor = fileInputStream.getFD();
				}
				// 将缓存数据解析成Bitmap对象
				Bitmap bitmap = null;
				if (fileDescriptor != null) {
					bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
				}
				if (bitmap != null) {
					// 将Bitmap对象添加到内存缓存当中
					addBitmapToLruCache(imageUrl, bitmap);
				}
				return bitmap;
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if (fileDescriptor == null && fileInputStream != null) {
					try {
						fileInputStream.close();
					} catch (IOException e) {
					}
				}
			}
			return null;
		}

		@Override
		protected void onPostExecute(Bitmap bitmap) {
			super.onPostExecute(bitmap);
			// 根据Tag找到相应的ImageView控件，将下载好的图片显示出来。
			ImageView imageView = (ImageView) imageViewManager.remove(imageUrl);
			Log.d("tag", "onPostExecute = " + imageUrl);
			if (imageView != null && imageView.getTag().equals(imageUrl)
					&& bitmap != null) {
				imageView.setImageBitmap(bitmap);
			}
			removeTaskFromLoadQueue(this);
			waitToLoadStrategy();
			// TODO
			fluchCache();
		}

		/**
		 * 建立HTTP请求，并获取Bitmap对象。
		 * 
		 * @param imageUrl
		 *            图片的URL地址
		 * @return 解析后的Bitmap对象
		 */
		private boolean downloadUrlToStream(String urlString,
				OutputStream outputStream) {
			HttpURLConnection urlConnection = null;
			BufferedOutputStream out = null;
			BufferedInputStream in = null;
			try {
				final URL url = new URL(urlString);
				urlConnection = (HttpURLConnection) url.openConnection();
				in = new BufferedInputStream(urlConnection.getInputStream(),
						8 * 1024);
				out = new BufferedOutputStream(outputStream, 8 * 1024);
				int b;
				while ((b = in.read()) != -1) {
					out.write(b);
				}
				return true;
			} catch (final IOException e) {
				e.printStackTrace();
			} finally {
				if (urlConnection != null) {
					urlConnection.disconnect();
				}
				try {
					if (out != null) {
						out.close();
					}
					if (in != null) {
						in.close();
					}
				} catch (final IOException e) {
					e.printStackTrace();
				}
			}
			return false;
		}

	}

	/**
	 * 清理缓存
	 */
	public void onDestroy() {
		cancelAllTasks();
		mLruCache = null;
		if (mSoftCache != null) {
			mSoftCache.clear();
			mSoftCache = null;
		}
		loadingQueue = null;
		waitingQueue = null;

		imageViewManager = null;
		manager = null;
		try {
			mDiskLruCache.close();
			mDiskLruCache = null;
		} catch (IOException e) {
		}
	}
}
