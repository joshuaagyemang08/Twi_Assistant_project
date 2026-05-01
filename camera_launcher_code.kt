// Add this section after the contactsLauncher block (around line 175)

    var cameraPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            cameraPermissionGranted = granted
            if (granted) {
                // Camera already started by openCamera() in DeviceActions
                // Just confirm to user
                tts.speak("Camera opened")
            } else {
                tts.speak("Camera permission denied")
            }
        }
    )

// Add this check in the LaunchedEffect(viewModel.executedAction) block AFTER the callPermission check:
            if (!cameraPermissionGranted && msg.contains("camera", ignoreCase = true)) {
                cameraLauncher.launch(Manifest.permission.CAMERA)
                return@LaunchedEffect
            }

// Add this check in the LaunchedEffect(viewModel.lastError) block AFTER the callPermission check:
            if (!cameraPermissionGranted && err.contains("camera", ignoreCase = true)) {
                cameraLauncher.launch(Manifest.permission.CAMERA)
                return@LaunchedEffect
            }
