package com.ruler.arcoremeasurement

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import kotlinx.android.synthetic.main.activity_measurement.*
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt


private const val MIN_OPENGL_VERSION = 3.0
private val TAG: String = ArcoreMeasurement::class.java.getSimpleName()
private var arFragment: ArFragment? = null
private val distanceModeArrayList = ArrayList<String>()
private var distanceMode: String = ""

// private var distanceModeTextView: TextView? = null
// private lateinit var distanceModeSpinner: Spinner
private val placedAnchors = ArrayList<Anchor>()
private val placedAnchorNodes = ArrayList<AnchorNode>()
private val sphereNodeArray = arrayListOf<Node>()
private val lineNodeArray = arrayListOf<Node>()
private var distanceCardViewRenderable: ViewRenderable? = null
private var render: TextView? = null
private val faceNodeArray = arrayListOf<Node>()

class ArcoreMeasurement : AppCompatActivity(), Scene.OnUpdateListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurement)

        start()
    }

    private fun start() {
        if (!checkIsSupportedDeviceOrFinish(this)) {
            Toast.makeText(applicationContext, "Device not supported", Toast.LENGTH_LONG)
                .show()
        }

        val distanceModeArray = resources.getStringArray(R.array.distance_mode)
        distanceModeArray.map { it ->
            distanceModeArrayList.add(it)
        }

        // arFragment = supportFragmentManager.findFragmentById(R.id.sceneform_fragment) as ArFragment?
        arFragment = sceneform_fragment as ArFragment
        // distanceModeTextView = findViewById(R.id.distance_view)
        render = findViewById(R.id.render_text)
        configureSpinner()
        // init()
        clearButton()

        arFragment!!.setOnTapArPlaneListener { hitResult: HitResult, plane: Plane?, motionEvent: MotionEvent? ->
            // Creating Anchor.
            when (distanceMode) {
                distanceModeArrayList[0] -> {
                    clearAllAnchors()
                    tapDistanceFromCamera(hitResult)
                    // placeAnchor(hitResult)
                }
                distanceModeArrayList[1] -> {
                    tapDistanceOf2Points(hitResult)
                }
                distanceModeArrayList[2] -> {
                    tapHeightOf2Points(hitResult)
                }
                else -> {
                    clearAllAnchors()
                    placeAnchor(hitResult)
                }
            }
        }
    }

    private fun tapHeightOf2Points(hitResult: HitResult) {
        if (placedAnchorNodes.size == 0) {
            placeAnchor(hitResult)

            Log.i(TAG, "开始：${placedAnchorNodes[0].worldPosition}")

            val frame = arFragment!!.arSceneView.arFrame

            val width = arFragment!!.arSceneView.width
            val height = arFragment!!.arSceneView.height
            val hits = frame!!.hitTest(width / 2.0f, height / 2.0f)

            for (hit in hits) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    val secondAnchorNode = AnchorNode(hit.createAnchor());
                    val newSecond = Vector3(secondAnchorNode.worldPosition.x, placedAnchorNodes[0].worldPosition.y,
                        secondAnchorNode.worldPosition.z)

                    drawRays(placedAnchorNodes[0], newSecond)
                    break
                }
            }
        } else {
            clearAllAnchors()
        }
    }

    private fun drawRays(firstAnchorNode: AnchorNode, secondWorldPosition: Vector3) {
        val firstWorldPosition = firstAnchorNode.worldPosition

        val length = calculateDistance(firstWorldPosition, secondWorldPosition)
        val difference = Vector3.subtract(firstWorldPosition, secondWorldPosition)
        val directionFromTopToBottom = difference.normalized()
        val rotationFromAToB = Quaternion.lookRotation(directionFromTopToBottom, Vector3.up())

        MaterialFactory.makeOpaqueWithColor(
            this,
            com.google.ar.sceneform.rendering.Color(0.33f, 0.87f, 0f)
        )
            .thenAccept { material ->
                val lineMode = ShapeFactory.makeCylinder(
                    0.005f,
                    difference.length(),
                    Vector3.zero(),
                    material
                )
                val lineNode = Node().apply {
                    setParent(firstAnchorNode)
                    renderable = lineMode
                    worldPosition =
                        Vector3.add(firstWorldPosition, secondWorldPosition).scaled(0.5f)
                    worldRotation = Quaternion.multiply(
                        rotationFromAToB,
                        Quaternion.axisAngle(Vector3(1.0f, 0.0f, 0.0f), 90f)
                    )
                }
                lineNodeArray.add(lineNode)

                ViewRenderable.builder()
                    .setView(this, R.layout.renderable_text)
                    .build()
                    .thenAccept { it ->
                        distanceCardViewRenderable = it
                        //render_text.text = "${String.format("%.1f", length * 100)}CM"
                        (it.view as TextView).text = "${String.format("%.1f", length * 100)}CM"
                        it.isShadowCaster = false
                        val FaceNode = FaceToCameraNode().apply {
                            setParent(lineNode)
                            localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 90f)
                            localPosition = Vector3(0f, 0.02f, 0f)
                            renderable = it
                        }
                        faceNodeArray.add(FaceNode)
                    }

            }
        distance_view.text = "%.1f".format(length * 100) + " cm"
    }


    private fun tapDistanceFromCamera(hitResult: HitResult) {
        placeAnchor(hitResult)
        measureDistanceFromCamera()
    }

    private fun measureDistanceFromCamera() {
        val frame = arFragment!!.arSceneView.arFrame
        if (placedAnchors.size >= 1) {
            val groundp = placedAnchors[0].pose
            val pose = frame!!.camera.pose

            val dx = groundp.tx() - pose.tx()
            val dy = groundp.ty() - pose.ty()
            val dz = groundp.tz() - pose.tz()
            val distance = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())

            // findViewById<TextView>(R.id.distance_view).text
            distance_view.text = "%.1f".format(distance * 100) + " cm"
        }

        // arFragment!!.arSceneView.scene.addOnUpdateListener(this)
    }


    // 模式布局
    private fun configureSpinner() {
        distanceMode = distanceModeArrayList[0]
        // distanceModeSpinner = findViewById(R.id.distance_mode_spinner)
        val distanceModeAdapter = ArrayAdapter(
            applicationContext,
            android.R.layout.simple_spinner_item,
            distanceModeArrayList
        )
        distanceModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        distance_mode_spinner.adapter = distanceModeAdapter
        distance_mode_spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val spinnerParent = parent as Spinner
                distanceMode = spinnerParent.selectedItem as String
                clearAllAnchors()
                setMode()
                toastMode()
                Log.i(TAG, "Selected arcore focus on ${distanceMode}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                clearAllAnchors()
                setMode()
                toastMode()
            }
        }
    }

    private fun clearAllAnchors() {
        for (anchorNode in placedAnchorNodes) {
            arFragment!!.arSceneView.scene.removeChild(anchorNode)
            anchorNode.isEnabled = false
            anchorNode.anchor!!.detach()
            anchorNode.setParent(null)
        }
        placedAnchors.clear()
        placedAnchorNodes.clear()
        sphereNodeArray.clear()
        lineNodeArray.clear()
        faceNodeArray.clear()
    }


    private fun setMode() {
        distance_view.text = distanceMode
    }

    private fun toastMode() {
        Toast.makeText(
            this,
            when (distanceMode) {
                distanceModeArrayList[0] -> "Find plane and tap somewhere"
                distanceModeArrayList[1] -> "Find plane and tap 2 points"
                distanceModeArrayList[2] -> "Find plane and tap 1 points"
                else -> "???"
            },
            Toast.LENGTH_SHORT
        )
            .show()
    }

    private fun clearButton() {
        // clearButton = findViewById(R.id.clearButton)
        clearButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                clearAllAnchors()
                setMode()
                toastMode()
            }
        })
    }

    private fun placeAnchor(hitResult: HitResult) {
        val anchor = hitResult.createAnchor()
        placedAnchors.add(anchor)

        val anchorNode = AnchorNode(anchor).apply {
            isSmoothed = true
            setParent(arFragment?.arSceneView?.scene)
        }
        placedAnchorNodes.add(anchorNode)

        MaterialFactory.makeOpaqueWithColor(
            this,
            com.google.ar.sceneform.rendering.Color(0.33f, 0.87f, 0f)
        )
            .thenAccept { material ->
                val sphere = ShapeFactory.makeSphere(0.01f, Vector3.zero(), material)
                sphereNodeArray.add(Node().apply {
                    setParent(anchorNode)
                    localPosition = Vector3.zero()
                    renderable = sphere
                })
            }.exceptionally {
                val builder = AlertDialog.Builder(this)
                builder.setMessage(it.message).setTitle("Error")
                val dialog = builder.create()
                dialog.show()
                return@exceptionally null
            }

        arFragment!!.arSceneView.scene.addOnUpdateListener(this)
    }

    private fun tapDistanceOf2Points(hitResult: HitResult) {
        if (placedAnchorNodes.size == 0) {
            placeAnchor(hitResult)
        } else if (placedAnchorNodes.size == 1) {
            placeAnchor(hitResult)

            drawLine(placedAnchorNodes[0], placedAnchorNodes[1])

            // placeMidAnchor(pose, distanceCardViewRenderable!!)
        } else {
            clearAllAnchors()
            placeAnchor(hitResult)
        }
    }

    private fun drawLine(firstAnchorNode: AnchorNode, secondAnchorNode: AnchorNode) {

        val firstWorldPosition = firstAnchorNode.worldPosition
        val secondWorldPosition = secondAnchorNode.worldPosition

        val length = calculateDistance(firstWorldPosition, secondWorldPosition)
        val difference = Vector3.subtract(firstWorldPosition, secondWorldPosition)

        val directionFromTopToBottom = difference.normalized()
        // val rotationFromAToB = Quaternion.rotationBetweenVectors(firstWorldPosition, secondWorldPosition)
        val rotationFromAToB = Quaternion.lookRotation(directionFromTopToBottom, Vector3.up())
        // val rotationFromAToB = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 90f)

        MaterialFactory.makeTransparentWithColor(
            this,
            com.google.ar.sceneform.rendering.Color(0.33f, 0.87f, 0f)
        )
            .thenAccept { material ->
                val lineMode = ShapeFactory.makeCylinder(
                    0.005f,
                    difference.length(),
                    Vector3.zero(),
                    material
                )
                val lineNode = Node().apply {
                    setParent(firstAnchorNode)
                    renderable = lineMode
                    worldPosition =
                        Vector3.add(firstWorldPosition, secondWorldPosition).scaled(0.5f)
                    worldRotation = Quaternion.multiply(
                        rotationFromAToB,
                        Quaternion.axisAngle(Vector3(1.0f, 0.0f, 0.0f), 90f)
                    )
                }
                lineNodeArray.add(lineNode)

                ViewRenderable.builder()
                    .setView(this, R.layout.renderable_text)
                    .build()
                    .thenAccept { it ->
                        distanceCardViewRenderable = it
                        //render_text.text = "${String.format("%.1f", length * 100)}CM"
                        (it.view as TextView).text = "${String.format("%.1f", length * 100)}CM"
                        it.isShadowCaster = false
                        val FaceNode = FaceToCameraNode().apply {
                            setParent(lineNode)
                            localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 90f)
                            // localRotation = worldRotation
                            localPosition = Vector3(0.02f, 0.02f, 0.02f)
                            renderable = it
                        }
                        faceNodeArray.add(FaceNode)
                    }
            }

        distance_view.text = "%.1f".format(length * 100) + " cm"
    }

    class FaceToCameraNode : Node() {
        override fun onUpdate(p0: FrameTime?) {
            scene?.let { scene ->
                val cameraPosition = scene.camera.worldPosition
                val nodePosition = this@FaceToCameraNode.worldPosition
                val direction = Vector3.subtract(cameraPosition, nodePosition)
                this.worldRotation =
                    Quaternion.lookRotation(direction, Vector3.up())
            }
        }
    }

    override fun onUpdate(frameTime: FrameTime) {
        when (distanceMode) {
            distanceModeArrayList[0] -> {
                measureDistanceFromCamera()
            }
            distanceModeArrayList[1] -> {
                measureDistanceOf2Points()
            }
            distanceModeArrayList[2] -> {
                measureHeightOf2Points()
            }
            else -> {
                measureDistanceFromCamera()
            }
        }
    }

    private fun measureHeightOf2Points() {
        val frame = arFragment!!.arSceneView.arFrame

        val width = arFragment!!.arSceneView.width
        val height = arFragment!!.arSceneView.height
        val hits = frame!!.hitTest(width / 2.0f, height / 2.0f)

        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val secondAnchorNode = AnchorNode(hit.createAnchor())

                if (placedAnchorNodes.isNotEmpty() && lineNodeArray.isNotEmpty() && faceNodeArray.isNotEmpty()) {
                    Log.i(TAG, "${placedAnchorNodes[0].worldPosition}")
                    Log.i(TAG, "Before: ${secondAnchorNode.worldPosition}")

                    val newSecond = Vector3(secondAnchorNode.worldPosition.x, placedAnchorNodes[0].worldPosition.y,
                        secondAnchorNode.worldPosition.z)
                    Log.i(TAG, "After: ${newSecond}")

                    val tmp = placedAnchorNodes[0]
                    lineNodeArray[0].setParent(null)
                    lineNodeArray[0].isEnabled = false
                    val FaceNode = faceNodeArray[0]
                    FaceNode.setParent(null)
                    FaceNode.isEnabled = false

                    clearAllAnchors()
                    placeAnchor_copy(tmp)
                    drawRays(placedAnchorNodes[0], newSecond)

                    val pose = frame.camera.pose
                    val cameraPose = Vector3(pose.tx(), pose.ty(), pose.tz())
                    Log.i(TAG, "座标点： ${placedAnchorNodes[0].worldPosition}")
                    Log.i(TAG, "更新两点距离重新渲染 屏幕中心:$newSecond 相机：$cameraPose")
                }
                break
            }
        }
    }

    private fun placeAnchor_copy(anchorNode: AnchorNode) {
        anchorNode.setParent(arFragment?.arSceneView?.scene)
        anchorNode.isEnabled = true

        placedAnchorNodes.add(anchorNode)

        MaterialFactory.makeOpaqueWithColor(
            this,
            com.google.ar.sceneform.rendering.Color(0.33f, 0.87f, 0f)
        )
            .thenAccept { material ->
                val sphere = ShapeFactory.makeSphere(0.01f, Vector3.zero(), material)
                sphereNodeArray.add(Node().apply {
                    setParent(anchorNode)
                    localPosition = Vector3.zero()
                    renderable = sphere
                })
            }.exceptionally {
                val builder = AlertDialog.Builder(this)
                builder.setMessage(it.message).setTitle("Error")
                val dialog = builder.create()
                dialog.show()
                return@exceptionally null
            }
        arFragment!!.arSceneView.scene.addOnUpdateListener(this)
    }

    private fun measureDistanceOf2Points() {
        if (placedAnchorNodes.size == 2) {
            val distanceMeter = calculateDistance(
                placedAnchorNodes[0].worldPosition,
                placedAnchorNodes[1].worldPosition
            )

            Log.i(TAG, "坐标1：${placedAnchorNodes[0].worldPosition}")
            Log.i(TAG, "坐标2：${placedAnchorNodes[1].worldPosition}")

            if (distanceCardViewRenderable != null) {
                (distanceCardViewRenderable!!.view as TextView).text =
                    "${String.format("%.1f", distanceMeter * 100)}CM"
            }

            distance_view.text = "%.1f".format(distanceMeter * 100) + " cm"
        }
        // arFragment!!.arSceneView.scene.addOnUpdateListener(this)
    }

    private fun calculateDistance(p1: Vector3, p2: Vector3): Float {
        return sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2) + (p1.z - p2.z).pow(2))
    }


    private fun checkIsSupportedDeviceOrFinish(activity: Activity): Boolean {
        val openGlVersionString =
            (Objects.requireNonNull(
                activity
                    .getSystemService(Context.ACTIVITY_SERVICE)
            ) as ActivityManager)
                .deviceConfigurationInfo
                .glEsVersion
        if (openGlVersionString.toDouble() < MIN_OPENGL_VERSION) {
            Log.e(TAG, "Sceneform requires OpenGL ES ${MIN_OPENGL_VERSION} later")
            Toast.makeText(
                activity,
                "Sceneform requires OpenGL ES ${MIN_OPENGL_VERSION} or later",
                Toast.LENGTH_LONG
            )
                .show()
            activity.finish()
            return false
        }
        return true
    }

}
