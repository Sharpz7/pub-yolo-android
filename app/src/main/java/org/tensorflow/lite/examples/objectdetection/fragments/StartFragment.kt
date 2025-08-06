package org.tensorflow.lite.examples.objectdetection.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import org.tensorflow.lite.examples.objectdetection.R
import org.tensorflow.lite.examples.objectdetection.databinding.FragmentStartBinding
import android.content.Intent
import org.tensorflow.lite.examples.objectdetection.ScreenCaptureService

class StartFragment : Fragment() {

    private var _binding: FragmentStartBinding? = null
    private val binding get() = _binding!!

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) navigateToCamera() else {}
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStartBinding.inflate(inflater, container, false)

        binding.btnCamera.setOnClickListener { onCameraClicked() }
        binding.btnScreen.setOnClickListener { navigateToScreen() }
        binding.btnStop.setOnClickListener { stopAndExit() }

        return binding.root
    }

    private fun onCameraClicked() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            navigateToCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun navigateToCamera() {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
            .navigate(StartFragmentDirections.actionStartToCamera())
    }

    private fun navigateToScreen() {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
            .navigate(StartFragmentDirections.actionStartToScreen())
    }

    private fun stopAndExit() {
        // Stop the screen capture service if it's running
        val screenCaptureIntent = Intent(requireContext(), ScreenCaptureService::class.java)
        requireContext().stopService(screenCaptureIntent)

        // Log that we're stopping services and exiting
        android.util.Log.d("StartFragment", "Stopping all services and exiting application")

        // Exit the app
        requireActivity().finishAffinity()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}