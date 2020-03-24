package com.wtz.libvideomaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import com.wtz.libvideomaker.utils.LogUtils;
import com.wtz.libvideomaker.utils.ShaderUtil;
import com.wtz.libvideomaker.utils.TextureUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class OffScreenRenderer implements WeGLSurfaceView.WeRenderer {
    private static final String TAG = "OffScreenRenderer";

    private Context mContext;

    private int mVertexShaderHandle;
    private int mFragmentShaderHandle;
    private int mProgramHandle;

    private static final int BYTES_PER_FLOAT = 4;

    /**
     * 在使用 VBO (顶点缓冲对象)之前，对象数据存储在客户端内存中，每次渲染时将其传输到 GPU 中。
     * 随着我们的场景越来越复杂，有更多的物体和三角形，这会给 GPU 和内存增加额外的成本。
     * 使用 VBO，信息将被传输一次，然后渲染器将从该图形存储器缓存中得到数据。
     * 要注意的是：VBO 必须创建在一个有效的 OpenGL 上下文中，即在 GLThread 中创建。
     */
    private int[] mVBOIds;// 顶点缓冲区对象 ID 数组
    private int mVertexCoordBytes;
    private int mTextureCoordBytes;

    /**
     * 使用 FBO 离屏渲染
     */
    private int mFBOId;

    /* ---------- 顶点坐标配置：start ---------- */
    // java 层顶点坐标
    private float[] mVertexCoordData;

    // 每个顶点坐标大小
    private static final int VERTEX_COORD_DATA_SIZE = 2;

    // Native 层存放顶点坐标缓冲区
    private FloatBuffer mVertexCoordBuffer;

    // 用来传入顶点坐标的句柄
    private int mVertexCoordHandle;

    // 用来传入顶点位置矩阵数值的句柄
    private int mPosMatrixUnifHandle;
    // 用来保存位置变换矩阵数值的数组
    private float[] mPositionMatrix;
    /* ---------- 顶点坐标配置：end ---------- */

    /* ---------- 纹理坐标配置：start ---------- */
    // java 层纹理坐标
    private float[] mTextureCoordData;

    // 每个纹理坐标大小
    private static final int TEXTURE_COORD_DATA_SIZE = 2;

    // Native 层存放纹理坐标缓冲区
    private FloatBuffer mTextureCoordBuffer;

    // 用来传入纹理坐标的句柄
    private int mTextureCoordHandle;
    /* ---------- 纹理坐标配置：end ---------- */

    // 用来传入纹理内容到片元着色器的句柄
    private int mTextureUniformHandle;

    // 此渲染器内部处理的源图像资源
    private int mSourceImageResId;
    private int mSourceImageWidth;
    private int mSourceImageHeight;
    // 此渲染器内部处理的源纹理内容句柄
    private int[] mSourceTextureIds;

    // 准备输出的纹理内容句柄
    private int[] mOutputTextureIds;
    private int[] mOldOutputTextureIds;

    public OffScreenRenderer(Context mContext, int sourceImageResId) {
        this.mContext = mContext;
        this.mSourceImageResId = sourceImageResId;
    }

    public int getOutputTextureId() {
        return mOutputTextureIds[0];
    }

    @Override
    public void onEGLContextCreated() {
        initShaderProgram();
        initCoordinatesData();
        initFBO();
        loadSourceTexture();
    }

    private void initShaderProgram() {
        // 创建着色器程序
        String vertexSource = ShaderUtil.readRawText(mContext, R.raw.vertex_offscreen_shader);
        String fragmentSource = ShaderUtil.readRawText(mContext, R.raw.texture_fragment_shader);
        int[] shaderIDs = ShaderUtil.createAndLinkProgram(vertexSource, fragmentSource);
        mVertexShaderHandle = shaderIDs[0];
        mFragmentShaderHandle = shaderIDs[1];
        mProgramHandle = shaderIDs[2];
        if (mProgramHandle <= 0) {
            throw new RuntimeException("initShaderProgram Error: createAndLinkProgram failed.");
        }

        // 获取顶点着色器和片元着色器中的变量句柄
        mVertexCoordHandle = GLES20.glGetAttribLocation(mProgramHandle, "a_Position");
        mTextureCoordHandle = GLES20.glGetAttribLocation(mProgramHandle, "a_TexCoordinate");
        mPosMatrixUnifHandle = GLES20.glGetUniformLocation(mProgramHandle, "u_PositionMatrix");
        mTextureUniformHandle = GLES20.glGetUniformLocation(mProgramHandle, "u_Texture");
    }

    private void initCoordinatesData() {
        /*
         *        归一化顶点坐标系                窗口纹理坐标系             FBO 纹理坐标系
         *               y                                                    y
         *               ↑                          ┆                         ↑
         * (-1,1)------(0,1)------(1,1)        ---(0,0)------(1,0)-->x   ---(0,1)------(1,1)
         *    ┆          ┆          ┆               ┆          ┆              ┆          ┆
         *    ┆          ┆          ┆               ┆          ┆              ┆          ┆
         * (-1,0)------(0,0)------(1,0)-->x         ┆          ┆              ┆          ┆
         *    ┆          ┆          ┆               ┆          ┆              ┆          ┆
         *    ┆          ┆          ┆               ┆          ┆              ┆          ┆
         * (-1,-1)-----(0,-1)-----(1,-1)          (0,1)------(1,1)       ---(0,0)------(1,0)-->x
         *                                          ↓                         ┆
         *                                          y
         */
        // 顶点坐标
        mVertexCoordData = new float[]{
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        };
        mVertexCoordBuffer = ByteBuffer
                .allocateDirect(mVertexCoordData.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(mVertexCoordData);
        mVertexCoordBuffer.position(0);
        mVertexCoordData = null;

        // 创建投影矩阵(4x4)返回值存储的数组
        mPositionMatrix = new float[16];

        // FBO 纹理坐标，上下左右四角要与顶点坐标一一对应起来
        mTextureCoordData = new float[]{
//                0f, 0f,
//                1f, 0f,
//                0f, 1f,
//                1f, 1f
                // 这里使用窗口坐标，并使用矩阵旋转来代替 FBO 坐标翻转
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        };
        mTextureCoordBuffer = ByteBuffer
                .allocateDirect(mTextureCoordData.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(mTextureCoordData);
        mTextureCoordBuffer.position(0);
        mTextureCoordData = null;

        // 创建 VBO
        mVBOIds = new int[1];// 顶点与纹理共用一个 VBO
        GLES20.glGenBuffers(1, mVBOIds, 0);
        // 绑定 VBO
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVBOIds[0]);
        // 为 VBO 分配内存
        mVertexCoordBytes = mVertexCoordBuffer.capacity() * BYTES_PER_FLOAT;
        mTextureCoordBytes = mTextureCoordBuffer.capacity() * BYTES_PER_FLOAT;
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                mVertexCoordBytes + mTextureCoordBytes,/* 这个缓冲区应该包含的字节数 */
                null,/* 初始化整个内存数据，这里先不设置 */
                GLES20.GL_STATIC_DRAW /* 这个缓冲区不会动态更新 */
        );
        // 为 VBO 设置数据
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER,
                0, mVertexCoordBytes, mVertexCoordBuffer);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER,
                mVertexCoordBytes, mTextureCoordBytes, mTextureCoordBuffer);
        // 解绑 VBO
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void initFBO() {
        // 创建 FBO
        int[] fboIds = new int[1];
        GLES20.glGenBuffers(1, fboIds, 0);
        if (fboIds[0] == 0) {
            throw new RuntimeException("initFBO glGenBuffers failed!");
        }
        mFBOId = fboIds[0];

        // 初始化要绑定的纹理
        mOutputTextureIds = new int[]{0};
        mOldOutputTextureIds = new int[]{0};
    }

    private void loadSourceTexture() {
        mSourceTextureIds = TextureUtils.genTexture2D(1);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSourceTextureIds[0]);
        Bitmap bitmap = BitmapFactory.decodeResource(mContext.getResources(), mSourceImageResId);
        mSourceImageWidth = bitmap.getWidth();
        mSourceImageHeight = bitmap.getHeight();
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        changePositionMatrix(width, height);
        bindTextureToFBO(width, height);
    }

    /**
     * 改变位置矩阵
     */
    private void changePositionMatrix(int width, int height) {
        LogUtils.d(TAG, "changePositionMatrix " + width + "x" + height);
        // 初始化单位矩阵
        Matrix.setIdentityM(mPositionMatrix, 0);

        // 设置正交投影
        float imageRatio = mSourceImageWidth * 1.0f / mSourceImageHeight;
        float containerRatio = width * 1.0f / height;
        if (containerRatio >= imageRatio) {
            // 容器比图像更宽一些，横向居中展示
            Matrix.orthoM(mPositionMatrix, 0,
                    -width / (height * imageRatio), width / (height * imageRatio),
                    -1f, 1f,
                    -1f, 1f);
        } else {
            // 容器比图像更高一些，纵向居中展示
            Matrix.orthoM(mPositionMatrix, 0,
                    -1, 1,
                    -height / (width / imageRatio), height / (width / imageRatio),
                    -1f, 1f);
        }

        // 沿 x 轴旋转 180 度
        // rotateM(float[] m, int mOffset, float a, float x, float y, float z)
        //  * @param a angle to rotate in degrees
        //  * @param x、y、z： 是否需要沿着 X、Y、Z 轴旋转， 0 不旋转，1f 需要旋转
        Matrix.rotateM(mPositionMatrix, 0, 180f, 1f, 0, 0);
    }

    private void bindTextureToFBO(int width, int height) {
        mOldOutputTextureIds[0] = mOutputTextureIds[0];

        /* ------ 1.创建要附加到 FBO 的纹理对象，此纹理将最终作为输出供外部使用 ------ */
        int[] textureIds = TextureUtils.genTexture2D(1);
        mOutputTextureIds[0] = textureIds[0];
        LogUtils.d(TAG, "bindTextureToFBO texture current=" + textureIds[0] + ",old=" + mOldOutputTextureIds[0]);
        // 绑定刚创建的附加纹理对象
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOutputTextureIds[0]);
        // 为此纹理分配内存
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width,
                height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        // 解绑纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        /* ------ 2.把纹理附加到 FBO ------ */
        // 绑定 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFBOId);
        // 把刚创建的纹理对象附加到 FBO
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, mOutputTextureIds[0], 0);
        int ret = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (ret != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Attach texture to FBO failed! FramebufferStatus:" + ret);
        }
        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        /* ------ 3.释放旧的纹理 ------ */
        if (mOldOutputTextureIds[0] != 0) {
            GLES20.glDeleteTextures(1, mOldOutputTextureIds, 0);
        }
    }

    @Override
    public void onDrawFrame() {
        // 绑定到 FBO 从而离屏渲染
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFBOId);

        // 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glClearColor(0f, 0f, 0f, 1.0f);

        // 使用程序对象 mProgramHandle 作为当前渲染状态的一部分
        GLES20.glUseProgram(mProgramHandle);

        // 准备设置坐标，先绑定 VBO ---------->
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVBOIds[0]);

        // 设置顶点坐标
        GLES20.glEnableVertexAttribArray(mVertexCoordHandle);
        GLES20.glVertexAttribPointer(mVertexCoordHandle, VERTEX_COORD_DATA_SIZE,
                GLES20.GL_FLOAT, false, 8,
                0 /* 此处为 VBO 中的数据偏移地址 */
        );
        // 设置投影矩阵，transpose 指明是否要转置矩阵，必须为 GL_FALSE
        GLES20.glUniformMatrix4fv(mPosMatrixUnifHandle, 1, false,
                mPositionMatrix, 0);

        // 设置纹理坐标
        GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
        GLES20.glVertexAttribPointer(mTextureCoordHandle, TEXTURE_COORD_DATA_SIZE,
                GLES20.GL_FLOAT, false, 8,
                mVertexCoordBytes /* 此处为 VBO 中的数据偏移地址 */
        );

        // 所有坐标设置完成后，解绑 VBO <----------
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        // 将纹理单元激活，并绑定到指定纹理对象数据
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSourceTextureIds[0]);

        // 将纹理数据传入到片元着色器 Uniform 变量中
        GLES20.glUniform1i(mTextureUniformHandle, 0);// 诉纹理标准采样器在着色器中使用纹理单元 0

        // 开始渲染图形：按照绑定的顶点坐标数组从第 1 个开始画 4 个点，一共 2 个三角形，组成一个矩形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 解绑 Texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        // 解绑 FBO，从而可以恢复到屏上渲染
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onEGLContextToDestroy() {
        if (mVertexCoordBuffer != null) {
            mVertexCoordBuffer.clear();
            mVertexCoordBuffer = null;
        }
        mPositionMatrix = null;
        if (mTextureCoordBuffer != null) {
            mTextureCoordBuffer.clear();
            mTextureCoordBuffer = null;
        }
        if (mProgramHandle > 0) {
            GLES20.glDetachShader(mProgramHandle, mVertexShaderHandle);
            GLES20.glDeleteShader(mVertexShaderHandle);
            mVertexShaderHandle = 0;

            GLES20.glDetachShader(mProgramHandle, mFragmentShaderHandle);
            GLES20.glDeleteShader(mFragmentShaderHandle);
            mFragmentShaderHandle = 0;

            GLES20.glDeleteProgram(mProgramHandle);
            mProgramHandle = 0;
        }
        if (mOutputTextureIds != null) {
            GLES20.glDeleteTextures(1, mOutputTextureIds, 0);
            mOutputTextureIds = null;
        }
        if (mOldOutputTextureIds != null) {
            GLES20.glDeleteTextures(1, mOldOutputTextureIds, 0);
            mOldOutputTextureIds = null;
        }
        if (mSourceTextureIds != null) {
            GLES20.glDeleteTextures(1, mSourceTextureIds, 0);
            mSourceTextureIds = null;
        }
        if (mVBOIds != null) {
            GLES20.glDeleteBuffers(mVBOIds.length, mVBOIds, 0);
            mVBOIds = null;
        }
    }

}
