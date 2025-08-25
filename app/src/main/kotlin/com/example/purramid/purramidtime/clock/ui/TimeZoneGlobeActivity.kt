package com.example.purramid.purramidtime.clock.ui

import android.animation.ValueAnimator
import android.animation.TypeEvaluator
import android.annotation.SuppressLint
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.clock.data.CityData
import com.example.purramid.purramidtime.clock.viewmodel.TimeZoneGlobeUiState
import com.example.purramid.purramidtime.clock.viewmodel.TimeZoneGlobeViewModel
import com.example.purramid.purramidtime.clock.viewmodel.TimeZoneOverlayInfo
import com.example.purramid.purramidtime.clock.viewmodel.RotationDirection
import com.google.android.filament.Box
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.utils.Float3
import dagger.hilt.android.AndroidEntryPoint
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.locationtech.jts.geom.Polygon
import com.google.android.material.snackbar.Snackbar
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

@AndroidEntryPoint
class TimeZoneGlobeActivity : AppCompatActivity() {

    private val TAG = "TimeZoneGlobeActivity"
    private val GLOBE_MODEL_ASSET_PATH = "scene.gltf"
    private val EARTH_RADIUS = 0.7f
    private val OVERLAY_RADIUS_FACTOR = 1.005f
    private val ROTATION_FACTOR = 0.15f

    private val viewModel: TimeZoneGlobeViewModel by viewModels()

    // Views
    private lateinit var sceneView: SceneView
    private lateinit var rotateLeftButton: Button
    private lateinit var rotateRightButton: Button
    private lateinit var resetButton: Button
    private lateinit var cityNorthernTextView: TextView
    private lateinit var citySouthernTextView: TextView
    private lateinit var utcOffsetTextView: TextView

    // Scene Objects
    private lateinit var modelLoader: ModelLoader
    private lateinit var materialLoader: MaterialLoader
    private var globeNode: ModelNode? = null
    private var overlayParentNode: Node? = null

    // Material cache
    private val materialCache = ConcurrentHashMap<Int, CompletableDeferred<MaterialInstance>>()

    // Touch rotation state
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    // City Pin Management
    private val cityPinNodes = mutableListOf<Node>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_zone_globe)

        // Bind Views
        sceneView = findViewById(R.id.sceneView)
        rotateLeftButton = findViewById(R.id.rotateLeftButton)
        rotateRightButton = findViewById(R.id.rotateRightButton)
        resetButton = findViewById(R.id.resetButton)
        cityNorthernTextView = findViewById(R.id.cityNorthernTextView)
        citySouthernTextView = findViewById(R.id.citySouthernTextView)
        utcOffsetTextView = findViewById(R.id.utcOffsetTextView)

        // Initialize SceneView Loaders
        modelLoader = ModelLoader(sceneView.engine, this)
        materialLoader = MaterialLoader(sceneView.engine, this)

        // Configure SceneView Camera
        sceneView.cameraNode.position = Position(x = 0.0f, y = 0.0f, z = 2.5f)

        // Start loading the globe model
        loadGlobeModel()

        // Setup interaction listeners
        setupTouchListener()
        setupButtonListeners()

        // Observe state changes from ViewModel
        observeViewModel()
    }

    private fun loadGlobeModel() {
        lifecycleScope.launch {
            try {
                val model = modelLoader.loadModel(GLOBE_MODEL_ASSET_PATH)
                if (model == null) {
                    handleError("Failed to load model: $GLOBE_MODEL_ASSET_PATH")
                    return@launch
                }
                val modelInstance = model.instance
                globeNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = EARTH_RADIUS
                ).apply {
                    viewModel.uiState.value.let { state ->
                        rotation = state.currentRotation
                    }
                    isPositionEditable = false
                    isRotationEditable = false
                    isScaleEditable = false
                }
                sceneView.addChildNode(globeNode!!)
                overlayParentNode = Node(engine = sceneView.engine).apply {
                    parent = globeNode
                }
                Log.d(TAG, "Purramid Globe model loaded and added.")
                viewModel.uiState.value.let { state ->
                    if (!state.isLoading && state.timeZoneOverlays.isNotEmpty()) {
                        createOrUpdateOverlays(state.timeZoneOverlays)
                    }
                }
            } catch (e: Exception) {
                handleError("Error loading globe model", e)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { uiState ->
                updateUiTexts(uiState)

                // Update globe rotation when state changes
                uiState.targetRotation?.let { targetRotation ->
                    globeNode?.let { node ->
                        animateGlobeRotation(node.rotation, targetRotation)
                    }
                }

                // Update overlays when they change
                if (!uiState.isLoading && uiState.timeZoneOverlays.isNotEmpty()) {
                    createOrUpdateOverlays(uiState.timeZoneOverlays)
                }

                // Update city pins when timezone changes
                updateCityPins(uiState.activeTimeZoneId)
            }
        }
    }

    private fun updateUiTexts(state: TimeZoneGlobeUiState) {
        state.activeTimeZoneInfo?.let { info ->
            cityNorthernTextView.text = info.northernCity
            citySouthernTextView.text = info.southernCity
            utcOffsetTextView.text = info.utcOffsetString
        } ?: run {
            cityNorthernTextView.text = ""
            citySouthernTextView.text = ""
            utcOffsetTextView.text = getString(R.string.time_zone_placeholder)
        }
    }

    private fun createOrUpdateOverlays(overlayInfos: List<TimeZoneOverlayInfo>) {
        if (overlayParentNode == null) return
        clearOverlays()
        overlayInfos.forEach { info ->
            val materialDeferred = getOrCreateMaterial(info.color)
            info.polygons.forEach { polygon ->
                createPolygonRenderable(polygon, materialDeferred, info.tzid)
            }
        }
    }

    private fun getOrCreateMaterial(colorArgb: Int): CompletableDeferred<MaterialInstance> {
        return materialCache.getOrPut(colorArgb) {
            val deferred = CompletableDeferred<MaterialInstance>()
            lifecycleScope.launch {
                try {
                    val a = android.graphics.Color.alpha(colorArgb) / 255f
                    val r = android.graphics.Color.red(colorArgb) / 255f
                    val g = android.graphics.Color.green(colorArgb) / 255f
                    val b = android.graphics.Color.blue(colorArgb) / 255f

                    val material = Material.Builder()
                        .build(sceneView.engine)
                        .createInstance().apply {
                            setParameter("baseColor", r, g, b, a)
                        }
                    material.setParameter("baseColor", r, g, b, a)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create material instance for color $colorArgb", e)
                    deferred.completeExceptionally(e)
                }
            }
            deferred
        }
    }

    private fun animateGlobeRotation(start: Rotation, end: Rotation) {
        Log.d(TAG, "Animating rotation from $start to $end")
        ValueAnimator.ofObject(RotationEvaluator(), start, end).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                globeNode?.rotation = animation.animatedValue as Rotation
            }
            start()
        }
    }

    private fun clearOverlays() {
        overlayParentNode?.childNodes?.toList()?.forEach { it.destroy() }
    }

    private fun createPolygonRenderable(
        polygon: Polygon,
        materialDeferred: CompletableDeferred<MaterialInstance>,
        tzId: String
    ) {
        lifecycleScope.launch {
            try {
                val materialInstance = materialDeferred.await()
                val engine = sceneView.engine
                val geometry = triangulatePolygon(polygon) ?: return@launch
                val (vertices, indices) = geometry

                val vertexBuffer = VertexBuffer.Builder()
                    .bufferCount(1).vertexCount(vertices.size)
                    .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
                    .build(engine)
                vertexBuffer.setBufferAt(engine, 0, vertices.toFloatBuffer())

                val indexBuffer = IndexBuffer.Builder()
                    .indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.UINT)
                    .build(engine)
                indexBuffer.setBuffer(engine, IntArray(indices.size) { indices[it] }.toIntBuffer())

                val bounds = calculateBounds(vertices)
                val renderableEntity = engine.entityManager.create()
                RenderableManager.Builder(1)
                    .boundingBox(bounds)
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, indices.size)
                    .material(0, materialInstance)
                    .culling(false)
                    .build(engine, renderableEntity)

                val node = Node(engine, renderableEntity).apply {
                    name = tzId
                }
                overlayParentNode?.addChildNode(node)

            } catch (e: Exception) {
                Log.e(TAG, "Error creating overlay renderable for $tzId", e)
            }
        }
    }

    private fun triangulatePolygon(polygon: Polygon): Pair<List<Float3>, List<Int>>? {
        try {
            if (polygon.exteriorRing.coordinates.size < 4) return null
            val vertices = mutableListOf<Float3>()
            val indices = mutableListOf<Int>()
            val overlayRadius = EARTH_RADIUS * OVERLAY_RADIUS_FACTOR
            val centroid = polygon.centroid
            vertices.add(latLonToFloat3(centroid.y, centroid.x, overlayRadius))
            val centroidIndex = 0
            for (i in 0 until polygon.exteriorRing.coordinates.size - 1) {
                val coord = polygon.exteriorRing.coordinates[i]
                vertices.add(latLonToFloat3(coord.y, coord.x, overlayRadius))
                val currentIndex = i + 1
                val nextIndex = if (i == polygon.exteriorRing.coordinates.size - 2) 1 else currentIndex + 1
                indices.add(centroidIndex)
                indices.add(currentIndex)
                indices.add(nextIndex)
            }
            return Pair(vertices, indices)
        } catch (e: Exception) {
            Log.e(TAG, "Error during triangulation", e)
            return null
        }
    }

    private fun calculateBounds(vertices: List<Float3>): Box {
        if (vertices.isEmpty()) return Box(0f, 0f, 0f, 0.1f, 0.1f, 0.1f)
        var minX = vertices[0].x; var minY = vertices[0].y; var minZ = vertices[0].z
        var maxX = vertices[0].x; var maxY = vertices[0].y; var maxZ = vertices[0].z
        for (i in 1 until vertices.size) {
            minX = minOf(minX, vertices[i].x); minY = minOf(minY, vertices[i].y); minZ = minOf(minZ, vertices[i].z)
            maxX = maxOf(maxX, vertices[i].x); maxY = maxOf(maxY, vertices[i].y); maxZ = maxOf(maxZ, vertices[i].z)
        }
        val halfExtentX = (maxX - minX) / 2f
        val halfExtentY = (maxY - minY) / 2f
        val halfExtentZ = (maxZ - minZ) / 2f
        val minHalfExtent = 0.001f
        return Box(
            (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
            maxOf(minHalfExtent, halfExtentX), maxOf(minHalfExtent, halfExtentY), maxOf(minHalfExtent, halfExtentZ)
        )
    }

    private fun latLonToFloat3(lat: Double, lon: Double, radius: Float): Float3 {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val x = radius * cos(latRad) * cos(lonRad)
        val y = radius * sin(latRad)
        val z = radius * cos(latRad) * sin(lonRad) * -1.0
        return Float3(x.toFloat(), y.toFloat(), z.toFloat())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            if (globeNode == null) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    val currentRot = globeNode!!.rotation
                    val deltaYaw = -dx * ROTATION_FACTOR * 2
                    val deltaPitch = -dy * ROTATION_FACTOR * 2
                    val newPitch = (currentRot.x + deltaPitch).coerceIn(-89f, 89f)
                    val newRotation = Rotation(x = newPitch, y = (currentRot.y + deltaYaw) % 360, z = currentRot.z)
                    viewModel.updateRotation(newRotation)
                    lastX = event.x
                    lastY = event.y
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun setupButtonListeners() {
        rotateLeftButton.setOnClickListener { viewModel.rotateToAdjacentZone(RotationDirection.LEFT) }
        rotateRightButton.setOnClickListener { viewModel.rotateToAdjacentZone(RotationDirection.RIGHT) }
        resetButton.setOnClickListener { viewModel.resetRotation() }
    }

    private fun updateCityPins(timeZoneId: String?) {
        clearCityPins()

        if (timeZoneId == null) return

        lifecycleScope.launch {
            try {
                val cities = viewModel.getCitiesForTimeZone(timeZoneId)
                val northernCities = cities.filter { it.latitude > 0 }.sortedBy { it.latitude }.reversed().take(2)
                val southernCities = cities.filter { it.latitude < 0 }.sortedBy { it.latitude }.take(2)

                northernCities.forEach { city ->
                    createCityPin(city, isNorthern = true)
                }

                southernCities.forEach { city ->
                    createCityPin(city, isNorthern = false)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading city pins for $timeZoneId", e)
            }
        }
    }

    private fun createCityPin(city: CityData, isNorthern: Boolean) {
        lifecycleScope.launch {
            try {
                val engine = sceneView.engine
                val pinRadius = 0.02f

                val pinColorArgb = if (isNorthern) {
                    android.graphics.Color.BLUE
                } else {
                    android.graphics.Color.GREEN
                }

                val r = android.graphics.Color.red(pinColorArgb) / 255f
                val g = android.graphics.Color.green(pinColorArgb) / 255f
                val b = android.graphics.Color.blue(pinColorArgb) / 255f

                val material = Material.Builder()
                    .build(sceneView.engine)
                    .createInstance().apply {
                        setParameter("baseColor", r, g, b, 1.0f)
                        setParameter("metallic", 0.0f)
                        setParameter("roughness", 0.5f)
                        setParameter("reflectance", 0.0f)
                    }

                val renderableEntity = engine.entityManager.create()
                val bounds = Box(0f, 0f, 0f, pinRadius, pinRadius, pinRadius)

                val (vertexBuffer, indexBuffer) = createSphereGeometry(pinRadius)

                RenderableManager.Builder(1)
                    .boundingBox(bounds)
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
                    .material(0, material)
                    .culling(false)
                    .build(engine, renderableEntity)

                val pinPosition = latLonToFloat3(city.latitude, city.longitude, EARTH_RADIUS + 0.01f)
                val pinNode = Node(engine, renderableEntity).apply {
                    position = Position(pinPosition.x, pinPosition.y, pinPosition.z)
                    name = "city_pin_${city.name}"
                }

                overlayParentNode?.addChildNode(pinNode)
                cityPinNodes.add(pinNode)

                Log.d(TAG, "Created city pin for ${city.name} at (${city.latitude}, ${city.longitude})")

            } catch (e: Exception) {
                Log.e(TAG, "Error creating city pin for ${city.name}", e)
            }
        }
    }

    private fun clearCityPins() {
        cityPinNodes.forEach { it.destroy() }
        cityPinNodes.clear()
    }

    private fun createSphereGeometry(radius: Float): Pair<VertexBuffer, IndexBuffer> {
        val segments = 8
        val vertices = mutableListOf<Float3>()
        val indices = mutableListOf<Int>()

        for (lat in 0..segments) {
            val theta = lat * Math.PI / segments
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)

            for (lon in 0..segments) {
                val phi = lon * 2 * Math.PI / segments
                val sinPhi = sin(phi)
                val cosPhi = cos(phi)

                val x = (radius * cosPhi * sinTheta).toFloat()
                val y = (radius * cosTheta).toFloat()
                val z = (radius * sinPhi * sinTheta).toFloat()

                vertices.add(Float3(x, y, z))
            }
        }

        for (lat in 0 until segments) {
            for (lon in 0 until segments) {
                val current = lat * (segments + 1) + lon
                val next = current + segments + 1

                indices.add(current)
                indices.add(next)
                indices.add(current + 1)

                indices.add(next)
                indices.add(next + 1)
                indices.add(current + 1)
            }
        }

        val engine = sceneView.engine
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertices.size)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vertices.toFloatBuffer())

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, IntArray(indices.size) { indices[it] }.toIntBuffer())

        return Pair(vertexBuffer, indexBuffer)
    }

    private fun handleError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        Snackbar.make(sceneView, message, Snackbar.LENGTH_LONG).show()
    }
}

// Extension functions for buffer conversion
private fun List<Float3>.toFloatBuffer(): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(this.size * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    this.forEach {
        buffer.put(it.x)
        buffer.put(it.y)
        buffer.put(it.z)
    }
    buffer.rewind()
    return buffer
}

private fun IntArray.toIntBuffer(): IntBuffer {
    val buffer = ByteBuffer.allocateDirect(this.size * 4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
    buffer.put(this)
    buffer.rewind()
    return buffer
}

// Animation Support
class RotationEvaluator : TypeEvaluator<Rotation> {
    override fun evaluate(fraction: Float, startValue: Rotation, endValue: Rotation): Rotation {
        val startX = startValue.x
        val startY = startValue.y
        val startZ = startValue.z

        val endX = endValue.x
        val endY = endValue.y
        val endZ = endValue.z

        val deltaY = endY - startY
        val adjustedDeltaY = when {
            deltaY > 180f -> deltaY - 360f
            deltaY < -180f -> deltaY + 360f
            else -> deltaY
        }

        val interpolatedX = startX + (endX - startX) * fraction
        val interpolatedY = startY + adjustedDeltaY * fraction
        val interpolatedZ = startZ + (endZ - startZ) * fraction

        return Rotation(
            x = interpolatedX,
            y = interpolatedY,
            z = interpolatedZ
        )
    }
}