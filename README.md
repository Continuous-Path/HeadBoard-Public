# Project HeadBoard
Project HeadBoard helps gamers control their mouse cursor using their head movement and facial gestures.

This fork contains **HeadBoard** (`/HeadBoard/`), the Android app. The companion **OpenBoard** keyboard lives in its own repo: [Continuous-Path/OpenBoard-HB](https://github.com/Continuous-Path/OpenBoard-HB).


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
