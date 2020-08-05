import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler
import io.reactivex.schedulers.Schedulers
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.embed.swing.SwingFXUtils
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Slider
import javafx.scene.control.Spinner
import javafx.scene.image.*
import model.DrawableMat
import model.Output
import model.ProcessedFrame
import org.bytedeco.javacv.*
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.opencv_core.Mat
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


class ControllerSequential {

    private var subscription: Disposable? = null

    // this grabs our frames from the stream
    private lateinit var grabber: FFmpegFrameGrabber

    // the FXML button
    @FXML
    private lateinit var toggleCamera: Button

    @FXML
    private lateinit var blurSpinner: Spinner<Int>

    @FXML
    private lateinit var toggleGrey: Button

    @FXML
    private lateinit var canvasFrame: Canvas

    @FXML
    private lateinit var originalView: ImageView

    @FXML
    private lateinit var displayMode: ComboBox<DISPLAY_MODE>

    @FXML
    private lateinit var thresholdSlider: Slider

    // a timer for acquiring the video stream
    private var timer: ScheduledExecutorService? = null

    private val cameraUri = "http://192.168.178.111:2222"

    // toggle to show color or gray image
    private var showGrayImage = false

    // what are we drawing?
    enum class DISPLAY_MODE {
        ORIGINAL, GRAY, CONTOUR, DIFF, BLUR
    }

    @FXML
    fun initialize() {
        val itemList = FXCollections.observableArrayList<DISPLAY_MODE>()
        DISPLAY_MODE.values().forEach {
            itemList.add(it)
        }
        displayMode.items = itemList
        displayMode.selectionModel.select(DISPLAY_MODE.ORIGINAL)

        // initial blur value
        blurSpinner.valueFactory.value = 10
    }


    /**
     * The action triggered by pushing the button on the GUI
     *
     * @param event
     * the push button event
     */
    @FXML
    private fun toggleCamera(event: ActionEvent?) {
        if (subscription != null) {
            stopAcquisition()
        } else {
            startAcquisition()
        }
    }

    private fun startAcquisition() {
        val run = Runnable {
            // this helps us count frames per second
            var fpsCounter = Counter()
            // Two converters, two worlds.
            val converterFX = JavaFXFrameConverter()
            val converterCV = OpenCVFrameConverter.ToMat()
            val converter2D = Java2DFrameConverter()

            // set up the frame grabber
            grabber = FFmpegFrameGrabber(cameraUri)
            println("Starting the frame grabber.")
            grabber.start()
            println("Started it.")

            // set up the canvas sizes and print some info
            grabber.apply {
                printGrabberInfo(this)
                // change the image frame
                canvasFrame.width = imageWidth.toDouble()
                canvasFrame.height = imageHeight.toDouble()
                originalView.fitWidth = imageWidth.toDouble()
                originalView.fitHeight = imageHeight.toDouble()
            }

            // create a buffer for the final image outputter
            var matBuffer = Mat(grabber.imageHeight, grabber.imageWidth, opencv_core.CV_8UC4)
            var buffer: ByteBuffer = matBuffer.createBuffer()
            val formatByte: WritablePixelFormat<ByteBuffer> = PixelFormat.getByteBgraPreInstance()

            // update UI
            Platform.runLater {
                toggleCamera.text = "Started feed"
                toggleCamera.disableProperty().value = false
            }

            // grab a frame to start with
            // keep track of the current time and the time of the source material
            val firstFrame = TimeUnit.MILLISECONDS.convert(grabber.grabImage().timestamp, TimeUnit.MICROSECONDS)
            val firstTime = System.currentTimeMillis()

            val mats = object {
                val gray = DrawableMat(Mat(grabber.imageHeight, grabber.imageWidth, opencv_core.CV_8UC1), ColorSpace.GRAY)
                val original = DrawableMat(Mat(grabber.imageHeight, grabber.imageWidth, opencv_core.CV_32SC1), ColorSpace.BGR)
                val toDraw = DrawableMat(Mat(grabber.imageHeight, grabber.imageWidth, opencv_core.CV_32SC1), ColorSpace.BGR)
            }

            // start grabbing frames
            while (true) {
                val grabbedImage = grabber.grabImage()


                // start processing
                val mat = converterCV.convert(grabbedImage)
                // prepare the containers
                // fill them up
                mat.copyTo(mats.original.mat)
                cvtColor(mats.original.mat, mats.gray.mat, CV_BGR2GRAY)

                // count fps
                fpsCounter.tick(33)

                // try the direct buffer approach
                println("going to draw to matbuffer")
                cvtColor(mats.original.mat, matBuffer, COLOR_BGR2BGRA)
                val pb = PixelBuffer(matBuffer.arrayWidth(), matBuffer.arrayHeight(), buffer, formatByte)
                val wi = WritableImage(pb)

                // before drawing, determine whether we should wait
                // what's the difference between now and the time the first frame came in on the host?
                val hostDiff = System.currentTimeMillis() - firstTime
                // what's the difference between this frame and the first frame, as experienced by the source material?
                val sourceDiff = TimeUnit.MILLISECONDS.convert(grabbedImage.timestamp, TimeUnit.MICROSECONDS) - firstFrame
                // what's the drift between host and source?
                // - 0 is exactly right, output the frame right now
                // - >0 means the host should wait, delay the emission (drift)
                // - <0 means the frame should already be out there, just output it
                val drift = sourceDiff - hostDiff
                println("hostDiff $hostDiff sourceDiff $sourceDiff drift $drift")
                if (drift > 0) {
                    Thread.sleep(drift)
                }
                originalView.image = wi
            }
        }
        Thread(run).start()

        toggleCamera.text = "Starting feed"
        toggleCamera.disableProperty().value = true
        println("startAcquisition done")
    }


    /**
     * Make sure that the given mat is in the BGR space.
     */
    private fun toColor(d: DrawableMat) {
        // if it's grayscale, convert
        if (d.space == ColorSpace.GRAY) {
            cvtColor(d.mat, d.mat, opencv_imgproc.CV_GRAY2BGR)
            d.space = ColorSpace.BGR
        }
    }

    /**
     * Pair each frame with the previous frame for easy access.
     */
    private fun pairPreviousAndCurrent(frames: Observable<Frame>): Observable<Array<Frame>>? {
        // first put each frame together with its previous frame, for easy processing
        return frames.scan(emptyArray<Frame>()) { previousAndCurrent: Array<Frame>, next: Frame ->
            when {
                previousAndCurrent.isEmpty() -> {
                    arrayOf(next)
                }
                previousAndCurrent.size == 1 -> {
                    arrayOf(previousAndCurrent[0], next)
                }
                else -> {
                    arrayOf(previousAndCurrent[1], next)
                }
            }
        }
    }

    private fun printGrabberInfo(grabber: FFmpegFrameGrabber) {
        grabber.apply {
            println("uri: $cameraUri")
            println("framerate: $videoFrameRate")
            println("has video: ${hasVideo()}")
            println("has audio: ${hasAudio()}")
            println("image height: $imageHeight")
            println("image width: $imageWidth")

        }
    }

    private fun stopAcquisition() {
        // release grabber
        if (grabber != null) {
            grabber.release()
        }

        // stop the active subscription
        if (subscription != null) {
            subscription!!.dispose()
        }

        // toggle UI, be ready for the next run
        toggleCamera.text = "Start"
    }

    /**
     * Update the [ImageView] in the JavaFX main thread
     *
     * @param view
     * the [ImageView] to update
     * @param image
     * the [Image] to show
     */
    private fun updateImageView(view: ImageView?, image: Image) {
        Utils.onFXThread(view!!.imageProperty(), image)
    }

    /**
     * On application close, stop the acquisition from the camera
     */
    fun setClosed() {
        stopAcquisition()
    }

    fun toggleGrey(actionEvent: ActionEvent) {
        showGrayImage = !showGrayImage
    }
}