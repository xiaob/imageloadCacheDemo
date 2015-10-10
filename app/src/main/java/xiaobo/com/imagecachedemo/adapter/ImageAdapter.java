package xiaobo.com.imagecachedemo.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import xiaobo.com.imagecachedemo.R;
import xiaobo.com.imagecachedemo.imageloader.ImageCache;

public class ImageAdapter extends ArrayAdapter<String> {
	private ImageCache manager;

	/**
	 * 记录每个子项的高度。
	 */
	private int mItemHeight = 0;

	public ImageAdapter(Context context, int textViewResourceId,
			String[] objects, GridView photoWall) {
		super(context, textViewResourceId, objects);
		manager = ImageCache.getInstanse(context);
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		final String url = getItem(position);
		View view;
		if (convertView == null) {
			view = LayoutInflater.from(getContext()).inflate(
					R.layout.photo_layout, null);
		} else {
			view = convertView;
		}
		final ImageView imageView = (ImageView) view.findViewById(R.id.photo);
		if (imageView.getLayoutParams().height != mItemHeight) {
			imageView.getLayoutParams().height = mItemHeight;
		}
		// 给ImageView设置一个Tag，保证异步加载图片时不会乱序
		imageView.setTag(url);
		imageView.setImageResource(R.drawable.empty_photo);
		manager.loadImages(imageView, url, false);
		return view;
	}

	/**
	 * 设置item子项的高度。
	 */
	public void setItemHeight(int height) {
		if (height == mItemHeight) {
			return;
		}
		mItemHeight = height;
		notifyDataSetChanged();
	}
}