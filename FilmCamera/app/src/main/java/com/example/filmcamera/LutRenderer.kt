package com.example.filmcamera

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 카메라 프리뷰(OES 텍스처)에 3D LUT를 적용해 그리는 렌더러.
 * Preview.SurfaceProvider에서 onSurfaceRequested()를 호출해 연결하며, GL 호출은 GL 스레드에서만 한다.
 */
class LutRenderer(
    private val glView: GLSurfaceView,
    initialLut: CubeLut,
    private val mainExecutor: Executor,
) : GLSurfaceView.Renderer {

    @Volatile var lutEnabled = true
        set(v) { field = v; glView.requestRender() }

    @Volatile private var pendingRequest: SurfaceRequest? = null
    @Volatile private var pendingLut: CubeLut? = null
    private var lut: CubeLut = initialLut
    private var lutUploaded = false

    /** 필름 교체: 다음 프레임에 GL 스레드에서 3D 텍스처를 새 LUT로 다시 올린다 */
    fun setLut(newLut: CubeLut) {
        pendingLut = newLut
        glView.requestRender()
    }
    @Volatile private var rotationDegrees = 0

    // GL 스레드 전용 상태
    private var program = 0
    private var camTex = 0
    private var lutTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var bufferW = 0
    private var bufferH = 0
    private var viewW = 1
    private var viewH = 1
    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private val local = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** 메인 스레드: 요청만 저장하고 GL 스레드가 처리하도록 깨운다 */
    fun onSurfaceRequested(request: SurfaceRequest) {
        request.setTransformationInfoListener(mainExecutor) { info ->
            rotationDegrees = info.rotationDegrees
            glView.requestRender()
        }
        pendingRequest = request
        glView.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GL 컨텍스트가 새로 만들어진 것이므로 이전 객체는 모두 무효
        surfaceTexture = null
        program = buildProgram(VERTEX, FRAGMENT)
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        camTex = tex[0]
        lutTex = tex[1]

        val oes = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(oes, camTex)
        GLES30.glTexParameteri(oes, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(oes, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(oes, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(oes, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        lutUploaded = false
        uploadLut()
    }

    /** GL 스레드: lut를 lutTex(3D 텍스처)로 업로드 */
    private fun uploadLut() {
        val n = lut.size
        val buf = ByteBuffer.allocateDirect(lut.data.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(lut.data)
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buf,
        )
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        lutUploaded = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingLut?.let { lut = it; pendingLut = null; uploadLut() }
        pendingRequest?.let { fulfil(it); pendingRequest = null }

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        // 화면 uv(0..1) -> 중앙 크롭 -> SurfaceTexture 변환.
        // 회전은 SurfaceTexture 변환 행렬에 이미 포함돼 있다(에뮬레이터에서 확인: rotationDegrees=90일 때
        // 별도 회전을 더하면 90도/180도 틀어진다). rotationDegrees는 크롭 비율 계산(가로/세로 교환)에만 쓴다.
        // TODO: 실제 폰에서도 똑바로 나오는지 확인할 것.
        val rot = rotationDegrees
        val dispW = if (rot % 180 == 0) bufferW else bufferH
        val dispH = if (rot % 180 == 0) bufferH else bufferW
        val viewAspect = viewW.toFloat() / viewH
        val imgAspect = dispW.toFloat() / dispH
        val sx = if (imgAspect > viewAspect) viewAspect / imgAspect else 1f
        val sy = if (imgAspect > viewAspect) 1f else imgAspect / viewAspect
        Matrix.setIdentityM(local, 0)
        Matrix.translateM(local, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(local, 0, sx, sy, 1f)
        Matrix.translateM(local, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(mvp, 0, stMatrix, 0, local, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTexMatrix"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLutSize"), lut.size.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLutOn"), if (lutEnabled) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCam"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** GL 스레드: CameraX가 요청한 해상도의 SurfaceTexture를 만들어 카메라에 넘긴다 */
    private fun fulfil(request: SurfaceRequest) {
        surfaceTexture?.release()
        val res = request.resolution
        bufferW = res.width
        bufferH = res.height
        val st = SurfaceTexture(camTex).apply {
            setDefaultBufferSize(res.width, res.height)
            setOnFrameAvailableListener { glView.requestRender() }
        }
        surfaceTexture = st
        val surface = Surface(st)
        request.provideSurface(surface, mainExecutor) { surface.release() }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "셰이더 컴파일 실패: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "프로그램 링크 실패: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
}"""

        // .cube 격자점은 텍셀 중심에 대응하므로 반 텍셀 보정을 해야 33점이 정확히 맞는다
        const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;
uniform samplerExternalOES uCam;
uniform sampler3D uLut;
uniform float uLutSize;
uniform float uLutOn;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec3 c = texture(uCam, vUv).rgb;
    vec3 p = c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
    vec3 graded = texture(uLut, p).rgb;
    outColor = vec4(mix(c, graded, uLutOn), 1.0);
}"""
    }
}
