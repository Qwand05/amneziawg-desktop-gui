AmneziaWG for Linux - portable Windows build (GUI only)
=======================================================

Run AmneziaWG.bat. Nothing is installed; the folder holds the application
(lib\) and this launcher. Settings and tunnel configurations are kept in
%APPDATA%\amnezia-awg, the log in %LOCALAPPDATA%\amnezia-awg\app.log.

Java: the launcher uses a runtime\ folder next to it when there is one,
otherwise the Java pointed to by JAVA_HOME, otherwise javaw.exe from the
PATH. Java 21 or newer is needed (for example Eclipse Temurin 21 from
https://adoptium.net). To make the folder self-contained, unpack a
Windows x64 JRE so that runtime\bin\javaw.exe exists.

What works on Windows: the tunnel list, the editor, importing and
exporting configurations (.conf, .zip, QR images, clipboard), QR codes,
settings, languages and the log viewer.

What does not work yet: connecting tunnels. The backend drives awg-quick
through polkit, which only exists on Linux, so the switches are disabled
here. A Windows backend on top of the official AmneziaWG Windows client
would be needed for that.

Options: AmneziaWG.bat --hidden starts in the tray.
