package com.wtz.videomaker.surfaceview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import com.wtz.libvideomaker.WeGLSurfaceView;
import com.wtz.libvideomaker.renderer.MultiImgOffRenderer;
import com.wtz.libvideomaker.renderer.NormalScreenRenderer;
import com.wtz.libvideomaker.renderer.OffScreenRenderer;
import com.wtz.libvideomaker.renderer.OnScreenRenderer;
import com.wtz.libvideomaker.renderer.SingleImgOffRenderer;
import com.wtz.videomaker.R;

import javax.microedition.khronos.egl.EGLContext;

public class MultiImgSurfaceView extends WeGLSurfaceView implements WeGLSurfaceView.WeRenderer, OffScreenRenderer.OnSharedTextureChangedListener {
    private static final String TAG = MultiImgSurfaceView.class.getSimpleName();

    private OffScreenRenderer mOffScreenRenderer;
    private OnScreenRenderer mOnScreenRenderer;

    public MultiImgSurfaceView(Context context) {
        this(context, null);
    }

    public MultiImgSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiImgSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        mOffScreenRenderer = new MultiImgOffRenderer(context, new int[] {
                R.drawable.tree, R.drawable.sunflower, R.drawable.lotus,
                R.drawable.carry_up, R.drawable.happy
        });
        mOffScreenRenderer.setSharedTextureChangedListener(this);

        mOnScreenRenderer = new NormalScreenRenderer(context);
        mOnScreenRenderer.setExternalTextureId(mOffScreenRenderer.getSharedTextureId());
    }

    @Override
    protected String getExternalLogTag() {
        return TAG;
    }

    @Override
    public void onSharedTextureChanged(int textureID) {
        mOnScreenRenderer.setExternalTextureId(textureID);
    }

    @Override
    protected WeRenderer getRenderer() {
        return this;
    }

    @Override
    public void onEGLContextCreated() {
        Log.d(TAG, "onEGLContextCreated");
        mOffScreenRenderer.onEGLContextCreated();
        mOnScreenRenderer.onEGLContextCreated();
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
        mOffScreenRenderer.onSurfaceChanged(width, height);
        mOnScreenRenderer.onSurfaceChanged(width, height);
    }

    @Override
    public void onDrawFrame() {
        Log.d(TAG, "onDrawFrame");
        mOffScreenRenderer.onDrawFrame();
        mOnScreenRenderer.onDrawFrame();
    }

    @Override
    public void onEGLContextToDestroy() {
        Log.d(TAG, "onEGLContextToDestroy");
        mOffScreenRenderer.onEGLContextToDestroy();
        mOnScreenRenderer.onEGLContextToDestroy();
    }

}