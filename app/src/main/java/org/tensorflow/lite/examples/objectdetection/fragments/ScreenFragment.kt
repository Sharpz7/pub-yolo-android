package org.tensorflow.lite.examples.objectdetection.fragments

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.tensorflow.lite.examples.objectdetection.ObjectDetectorHelper
import org.tensorflow.lite.examples.objectdetection.R
import org.tensorflow.lite.examples.objectdetection.ScreenCaptureService
import org.tensorflow.lite.examples.objectdetection.databinding.FragmentScreenBinding
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.widget.SwitchCompat
import android.preference.PreferenceManager

class ScreenFragment : Fragment() {

    private var _binding: FragmentScreenBinding? = null
    private val binding get() = _binding!!

    private var screenCaptureService: ScreenCaptureService? = null
    private var bound = false
    private var hasValidPermission = false

    // Convenience reference once the service is connected
    private val detectorHelper: ObjectDetectorHelper?
        get() = screenCaptureService?.getObjectDetectorHelper()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d("ScreenFragment", "Service connected")
            try {
                val binder = service as ScreenCaptureService.LocalBinder
                screenCaptureService = binder.getService()
                bound = true

                // Since we're using a system overlay, there's no need for an on-frame listener
                // The service will handle drawing the results directly

                // Initialize UI once service is ready
                initBottomSheetControls()
                updateControlsUi()
                // Update inference time display on each frame
                screenCaptureService?.setOnFrameListener { results, inferenceTime, imageHeight, imageWidth ->
                    requireActivity().runOnUiThread {
                        binding.bottomSheetLayout.inferenceTimeVal.text = String.format("%d ms", inferenceTime)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenFragment", "Error in service connection", e)
                bound = false
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d("ScreenFragment", "Service disconnected")
            bound = false
        }
    }

    private val projectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("ScreenFragment", "Permission result: ${result.resultCode}, data: ${result.data}")
            Log.d("ScreenFragment", "RESULT_OK = ${Activity.RESULT_OK}, RESULT_CANCELED = ${Activity.RESULT_CANCELED}")

            if (result.data != null) {
                Log.d("ScreenFragment", "Data extras: ${result.data!!.extras}")
            }

            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d("ScreenFragment", "Permission granted, starting service")
                hasValidPermission = true
                startScreenCaptureService(result.resultCode, result.data!!)
            } else {
                Log.w("ScreenFragment", "Permission denied or cancelled (resultCode: ${result.resultCode})")
                hasValidPermission = false
                // Permission denied or cancelled. Return to previous screen.
                requireActivity().onBackPressed()
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(requireContext())) {
                Log.d("ScreenFragment", "Overlay permission granted")
                requestScreenCapturePermission()
            } else {
                Log.e("ScreenFragment", "Overlay permission was not granted.")
                // Handle the case where the user denies the permission.
                // You might want to show a message and close the fragment.
                requireActivity().onBackPressed()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestOverlayPermission() // Start with overlay permission

        // Hide in-layout warm/cold indicator; overlay indicator is used instead
        binding.warmColdIndicator.visibility = View.GONE

        // Set listeners for UI even before service connects (they will act when helper is available)
        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        // Guard against multiple initializations
        if (binding.bottomSheetLayout.thresholdMinus.hasOnClickListeners()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val switchShowBoxes = binding.bottomSheetLayout.root.findViewById<SwitchCompat>(R.id.switch_show_boxes)
        switchShowBoxes.isChecked = prefs.getBoolean("pref_show_boxes", true)
        switchShowBoxes.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_show_boxes", isChecked).apply()
            // OverlayView in service will check preference on next draw automatically
        }

        // Threshold controls
        binding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            detectorHelper?.let {
                if (it.threshold >= 0.1f) {
                    it.threshold -= 0.1f
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            } ?: showHelperUnavailable()
        }

        binding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            detectorHelper?.let {
                if (it.threshold <= 0.8f) {
                    it.threshold += 0.1f
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            } ?: showHelperUnavailable()
        }

        // Max results controls
        binding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            detectorHelper?.let {
                if (it.maxResults > 1) {
                    it.maxResults--
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            } ?: showHelperUnavailable()
        }

        binding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            detectorHelper?.let {
                if (it.maxResults < 5) {
                    it.maxResults++
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            } ?: showHelperUnavailable()
        }

        // Threads controls
        binding.bottomSheetLayout.threadsMinus.setOnClickListener {
            detectorHelper?.let {
                if (it.numThreads > 1) {
                    it.numThreads--
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            } ?: showHelperUnavailable()
        }

        binding.bottomSheetLayout.threadsPlus.setOnClickListener {
            detectorHelper?.let {
                if (it.numThreads < 4) {
                    it.numThreads++
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            } ?: showHelperUnavailable()
        }

        // Delegate selector
        binding.bottomSheetLayout.spinnerDelegate.setSelection(0, false)
        binding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                detectorHelper?.let {
                    it.currentDelegate = position
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { /* no op */ }
        }

        // Model selector
        binding.bottomSheetLayout.spinnerModel.setSelection(0, false)
        binding.bottomSheetLayout.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                detectorHelper?.let {
                    it.currentModel = position
                    it.clearObjectDetector()
                    updateControlsUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { /* no op */ }
        }
    }

    private fun updateControlsUi() {
        detectorHelper?.let { helper ->
            binding.bottomSheetLayout.maxResultsValue.text = helper.maxResults.toString()
            binding.bottomSheetLayout.thresholdValue.text = String.format("%.2f", helper.threshold)
            binding.bottomSheetLayout.threadsValue.text = helper.numThreads.toString()
        }
    }

    private fun showHelperUnavailable() {
        Toast.makeText(requireContext(), "Service not ready yet", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(requireContext())) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            overlayPermissionLauncher.launch(intent)
        } else {
            // Permission already granted
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        Log.d("ScreenFragment", "Requesting screen capture permission")
        try {
            val projectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val permissionIntent = projectionManager.createScreenCaptureIntent()
            Log.d("ScreenFragment", "Launching permission intent: $permissionIntent")
            projectionPermissionLauncher.launch(permissionIntent)
        } catch (e: Exception) {
            Log.e("ScreenFragment", "Error requesting permission", e)
            requireActivity().onBackPressed()
        }
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        if (!hasValidPermission) {
            Log.e("ScreenFragment", "Attempting to start service without valid permission")
            return
        }

        Log.d("ScreenFragment", "Starting screen capture service with resultCode: $resultCode")
        val serviceIntent = Intent(requireContext(), ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        try {
            requireContext().startForegroundService(serviceIntent)
            requireContext().bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("ScreenFragment", "Failed to start service", e)
            requireActivity().onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (bound) {
            requireContext().unbindService(connection)
            bound = false
        }
        screenCaptureService = null
        // Stop the screen capture service to remove its overlays
        requireContext().stopService(Intent(requireContext(), ScreenCaptureService::class.java))
        _binding = null
    }
}