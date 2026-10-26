AmneziaWG for Linux - portable Windows build
============================================

Run AmneziaWG.bat. Nothing is installed; the folder holds the application
(lib\) and this launcher. Settings and tunnel configurations are kept in
%APPDATA%\amnezia-awg, the app's log in %LOCALAPPDATA%\amnezia-awg\app.log.

Java: the launcher uses a runtime\ folder next to it when there is one,
otherwise the Java pointed to by JAVA_HOME, otherwise javaw.exe from the
PATH. Java 21 or newer is needed (for example Eclipse Temurin 21 from
https://adoptium.net). To make the folder self-contained, unpack a
Windows x64 JRE so that runtime\bin\javaw.exe exists.

Tunnels: they are run by the official AmneziaWG for Windows client, the
same way it runs its own. The app looks for amneziawg.exe and awg.exe in
  1. an amneziawg\ folder next to AmneziaWG.bat (a client you built),
  2. C:\Program Files\AmneziaWG\ (the client's installer puts it there),
  3. the PATH.
Without the client the app still works for editing, importing and
exporting configurations, but the switches are disabled.

Administrator rights: connecting and disconnecting a tunnel installs or
removes its Windows service, which needs administrator rights, so Windows
asks for approval (UAC) each time. To avoid that, and to see live traffic
statistics and the client log, start AmneziaWG.bat with "Run as
administrator".

Tunnel services start automatically with Windows: a tunnel that is up
when you shut down comes back after a reboot, even without this app.
Disconnect it first if you don't want that.

Options: AmneziaWG.bat --hidden starts in the tray;
AmneziaWG.bat --toggle <tunnel> connects or disconnects a tunnel.
