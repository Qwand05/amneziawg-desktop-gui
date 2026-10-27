AmneziaWG desktop GUI - portable Windows build
==============================================

Run AmneziaWG.exe. Nothing is installed; the folder holds the application
(lib\) and this launcher. Settings and tunnel configurations are kept in
%APPDATA%\amnezia-awg, the app's log in %LOCALAPPDATA%\amnezia-awg\app.log.

Java: the launcher uses a runtime\ folder next to it when there is one,
otherwise the Java pointed to by JAVA_HOME, otherwise javaw.exe from the
PATH. Java 21 or newer is needed (for example Eclipse Temurin 21 from
https://adoptium.net). To make the folder self-contained, unpack a
Windows x64 JRE so that runtime\bin\javaw.exe exists.

Tunnels: they are run by the official AmneziaWG for Windows client, the
same way it runs its own. The app looks for amneziawg.exe and awg.exe in
  1. an amneziawg\ folder next to AmneziaWG.exe (a client you built),
  2. C:\Program Files\AmneziaWG\ (the client's installer puts it there),
  3. the PATH.
Without the client the app still works for editing, importing and
exporting configurations, but the switches are disabled.

The tunnel list shows this app's own tunnels (%APPDATA%\amnezia-awg).
Tunnels created in the official client's window are stored encrypted in
its protected folder, which no other program can read: export them there
("Export all tunnels to zip") and import the zip here with the + button.

Administrator rights: connecting and disconnecting a tunnel installs or
removes its Windows service, which needs administrator rights. Windows asks
for approval (UAC) once per session, the first time a tunnel is switched;
that starts a small elevated helper which does the rest without asking
again, and exits with the app. Once it runs, live traffic statistics and
the client log work too. Starting AmneziaWG.exe with "Run as administrator"
avoids even that one prompt.

Importing: the + button takes .conf files and archives of them (.zip, .7z,
.rar, .tar, .tar.gz…); Windows' own tar.exe reads the non-zip ones.

Tunnel services start automatically with Windows: a tunnel that is up
when you shut down comes back after a reboot, even without this app.
Disconnect it first if you don't want that.

Disconnect tunnels while offline (Settings, off by default): when no
network adapter other than a tunnel's has a default route for five
seconds, the app takes its running tunnels down, and brings the same ones
back once the connection has been back for five seconds. It checks with
PowerShell (Get-NetRoute) whenever the adapters change. Tunnels started
from the official client's own window are left alone.

Options: AmneziaWG.exe --hidden starts in the tray;
AmneziaWG.exe --toggle <tunnel> connects or disconnects a tunnel.
