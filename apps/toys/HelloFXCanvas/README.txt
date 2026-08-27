====================================
HOW TO RUN
====================================

1) Build the JavaFX SDK by running "mvn install -DskipTests" in the root folder (the HelloFXCanvas jar itself is built with Ant from apps/toys/HelloFXCanvas)
2) Run "java --module-path sdk/target/sdk/lib --add-modules javafx.controls,javafx.swt --enable-native-access=ALL-UNNAMED -cp apps/toys/HelloFXCanvas/dist/HelloFXCanvas.jar:<path-to-swt-jar> hellofxcanvas.HelloFXCanvas" (an SWT jar for your platform is downloaded to modules/javafx.swt/target/swt-libs by the build)
3) In case of macOS we also need to pass -XstartOnFirstThread JVM argument to make SWT work with JavaFX
4) In case of Windows path separator should be changed from ':' to ';'
5) In case of Linux running on Wayland, we need to override X11 as backend for GDK, this can be done by using the command "export GDK_BACKEND=x11"
=================================================================================================================
