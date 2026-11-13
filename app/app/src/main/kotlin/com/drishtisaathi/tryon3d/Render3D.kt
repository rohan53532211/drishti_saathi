package com.drishtisaathi.tryon3d

import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin Filament host for one transparent TextureView: GLB models via gltfio, one sun + soft ambient light,
 * soft shadows, and a Choreographer render loop. Used by the frame viewer, the live 3D try-on (over the camera
 * preview) and the face avatar. Everything is on the main thread.
 *
 * [onFrame] runs before each render (place models there) and gets the frame time in ms.
 */
class Render3D(private val transparent: Boolean = true) {
    val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val sun = EntityManager.get().create()
    private val assets = LinkedHashMap<String, FilamentAsset>()
    private var running = false
    private var destroyed = false
    var width = 1
        private set
    var height = 1
        private set
    var onFrame: ((Long) -> Unit)? = null
    var onResize: ((Int, Int) -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || destroyed) return
            Choreographer.getInstance().postFrameCallback(this)
            try {
                onFrame?.invoke(frameTimeNanos / 1_000_000)
                val sc = swapChain ?: return
                if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            } catch (e: Exception) {
                Log.w(TAG, "render failed", e)
            }
        }
    }

    init {
        view.scene = scene
        view.camera = camera
        if (transparent) {
            view.blendMode = View.BlendMode.TRANSLUCENT
            renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
        } else {
            renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.93, 0.95, 0.95, 1.0) }
        }
        view.setShadowingEnabled(true)
        view.setShadowType(View.ShadowType.PCF)
        // one environment light (flat ambient, no image file needed) + one soft sun from above-front
        scene.indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 1f, 1f)).intensity(32_000f).build(engine)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.98f, 0.95f).intensity(70_000f)
            .direction(0.25f, -0.7f, -0.65f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply { mapSize = 1024; shadowBulbRadius = 0.04f })
            .build(engine, sun)
        scene.addEntity(sun)
        camera.setExposure(16f, 1f / 125f, 100f)
        uiHelper.isOpaque = !transparent
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }

            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }

            override fun onResized(w: Int, h: Int) {
                width = maxOf(1, w); height = maxOf(1, h)
                view.viewport = Viewport(0, 0, width, height)
                onResize?.invoke(width, height)
            }
        }
    }

    fun attach(textureView: TextureView) {
        uiHelper.attachTo(textureView)
    }

    fun start() {
        if (running || destroyed) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Loads a GLB under [key] (replacing any model with that key). Throws if the file is not a usable GLB. */
    fun load(key: String, glb: ByteArray, castShadows: Boolean = true, receiveShadows: Boolean = true): FilamentAsset {
        remove(key)
        val buf = ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb).also { it.flip() }
        val asset = assetLoader.createAsset(buf) ?: throw IllegalArgumentException("not a GLB")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        val rm = engine.renderableManager
        for (e in asset.renderableEntities) {
            val i = rm.getInstance(e)
            rm.setCastShadows(i, castShadows)
            rm.setReceiveShadows(i, receiveShadows)
        }
        scene.addEntities(asset.entities)
        assets[key] = asset
        return asset
    }

    fun has(key: String) = assets.containsKey(key)

    fun remove(key: String) {
        val a = assets.remove(key) ?: return
        scene.removeEntities(a.entities)
        assetLoader.destroyAsset(a)
    }

    /** Sets a model's root transform (column-major 4×4). */
    fun setRoot(key: String, m: FloatArray) {
        val a = assets[key] ?: return
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(a.root), m)
    }

    /** Sets a named node's local transform (e.g. "temple_R"); false if the model has no such node. */
    fun setNode(key: String, node: String, m: FloatArray): Boolean {
        val a = assets[key] ?: return false
        val e = a.getFirstEntityByName(node)
        if (e == 0) return false
        val tm = engine.transformManager
        val i = tm.getInstance(e)
        if (i == 0) return false
        tm.setTransform(i, m)
        return true
    }

    /** Shows or hides a named node (e.g. "contact_shadow" in the viewer, where there is no face). */
    fun setNodeVisible(key: String, node: String, visible: Boolean) {
        val a = assets[key] ?: return
        val e = a.getFirstEntityByName(node)
        if (e == 0) return
        if (visible) scene.addEntity(e) else scene.removeEntity(e)
    }

    /** Invisible occluder: writes depth only, drawn first, so temples behind the head are hidden. */
    fun makeOccluder(key: String) {
        val a = assets[key] ?: return
        val rm = engine.renderableManager
        for (e in a.renderableEntities) {
            val i = rm.getInstance(e)
            rm.setPriority(i, 0)
            rm.setCastShadows(i, false)
            for (p in 0 until rm.getPrimitiveCount(i)) rm.getMaterialInstanceAt(i, p).setColorWrite(false)
        }
    }

    /** Direction the sun light travels (it is normalised by Filament). */
    fun setSunDirection(x: Float, y: Float, z: Float) {
        val lm = engine.lightManager
        lm.setDirection(lm.getInstance(sun), x, y, z)
    }

    /** Orthographic camera in view pixels: x right, y up (0 at the top edge, −height at the bottom), z towards the viewer. */
    fun orthoPixels() {
        camera.setProjection(Camera.Projection.ORTHO, 0.0, width.toDouble(), -height.toDouble(), 0.0, 0.0, 20_000.0)
        camera.lookAt(0.0, 0.0, 10_000.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }

    /**
     * The real camera: at the origin looking down −Z (y up), with a projection built from the camera intrinsics
     * (engine ViewIntrinsics.projection) that maps camera space straight onto this view's pixels.
     */
    fun intrinsicsCamera(projection: DoubleArray, near: Double, far: Double) {
        camera.setCustomProjection(projection, near, far)
        camera.lookAt(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)
    }

    /** Perspective camera at [eye] looking at [target] (y up); units of the scene. */
    fun perspectiveLookAt(eye: DoubleArray, target: DoubleArray, fovDeg: Double = 30.0, near: Double = 5.0, far: Double = 500.0) {
        camera.setProjection(fovDeg, width.toDouble() / height, near, far, Camera.Fov.VERTICAL)
        camera.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], 0.0, 1.0, 0.0)
    }

    /** Perspective camera looking at the origin from [distanceMm] along −Z (the front of a frame or avatar). */
    fun perspectiveFront(distanceMm: Double, fovDeg: Double = 30.0) {
        camera.setProjection(fovDeg, width.toDouble() / height, 10.0, 5000.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 0.0, -distanceMm, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }

    fun destroy() {
        if (destroyed) return
        stop()
        destroyed = true
        uiHelper.detach()
        assets.keys.toList().forEach { remove(it) }
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        engine.destroyEntity(sun)
        scene.indirectLight?.let { engine.destroyIndirectLight(it) }
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    companion object {
        private const val TAG = "Render3D"
        @Volatile private var initResult: Boolean? = null

        /** Loads the native libraries once; false (never throws) when this phone cannot run Filament. */
        fun available(): Boolean = initResult ?: synchronized(this) {
            initResult ?: runCatching { Filament.init(); Gltfio.init(); true }
                .onFailure { Log.w(TAG, "3D renderer unavailable", it) }.getOrDefault(false).also { initResult = it }
        }
    }
}
