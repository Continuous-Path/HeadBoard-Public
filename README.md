# HeadBoard

HeadBoard lets you control an Android device with head movement and facial gestures,
using the front-facing camera. It is built for people who cannot use a touchscreen in the
ordinary way — moving the cursor by turning your head, and clicking with a gesture such as
opening your mouth or raising an eyebrow.

It is maintained by the [Continuous Path Foundation](https://github.com/Continuous-Path),
a 501(c)(3) nonprofit, and works alongside two companions:

- **[JustType](https://github.com/Continuous-Path/JustType)** — an accessibility keyboard
  that HeadBoard can drive directly as a joystick.
- **[OpenBoard-HB](https://github.com/Continuous-Path/OpenBoard-HB)** — a conventional
  keyboard fork that accepts HeadBoard's injected pointer events, so you can swipe-type
  with your head.

The Android app lives in `/HeadBoard/`.


# Model used
MediaPipe Face Landmark Detection API [Task Guide](https://developers.google.com/mediapipe/solutions/vision/face_landmarker)  
[MediaPipe BlazeFace Model Card](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf)  
[MediaPipe FaceMesh Model Card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf)  
[Mediapipe Blendshape V2 Model Card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf)  


# Out-of-Scope Applications
* This project is not intended for human life-critical decisions 
* Predicted face landmarks do not provide facial recognition or identification and do not store any unique face representation.

## License

HeadBoard is licensed under the [Apache License 2.0](./LICENSE).

It is a fork of [Project GameFace](https://github.com/google/project-gameface)
(Copyright 2024 Google LLC, Apache-2.0). See [NOTICE](./NOTICE) for the full
attribution list, including MediaPipe and the bundled face-landmark models.
"Google" and "Project GameFace" are marks of Google LLC; HeadBoard is not
affiliated with or endorsed by Google.

Contributions are accepted under the [DCO](./CONTRIBUTING.md); participation is
governed by our [Code of Conduct](./CODE_OF_CONDUCT.md).
