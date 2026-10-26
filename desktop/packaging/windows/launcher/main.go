// AmneziaWG.exe: the portable Windows build's launcher. It starts the app
// (the jars in lib\) with javaw.exe, so no console window appears, passing
// its arguments (--hidden, --toggle <tunnel>) through. Java is taken from a
// runtime\ folder next to it, else JAVA_HOME, else the PATH.
//
// Built by desktop/build.gradle.kts (buildWindowsLauncher) with Go's own
// cross-compiler; only the standard library is used.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

const mainClass = "org.amnezia.awg.desktop.MainKt"

func main() {
	self, err := os.Executable()
	if err != nil {
		fail("Cannot locate AmneziaWG.exe: " + err.Error())
	}
	dir := filepath.Dir(self)
	javaw := findJavaw(dir)
	if javaw == "" {
		fail("AmneziaWG needs Java 21 or newer.\n\n" +
			"Install it (for example Eclipse Temurin 21 from adoptium.net), " +
			"or unpack a Java runtime into the \"runtime\" folder next to AmneziaWG.exe.")
	}
	// The java launcher expands lib\* to every jar in lib itself.
	args := append([]string{"-cp", filepath.Join(dir, "lib", "*"), mainClass}, os.Args[1:]...)
	cmd := exec.Command(javaw, args...)
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		fail("Cannot start " + javaw + ":\n" + err.Error())
	}
}

func findJavaw(dir string) string {
	candidates := []string{filepath.Join(dir, "runtime", "bin", "javaw.exe")}
	if home := os.Getenv("JAVA_HOME"); home != "" {
		candidates = append(candidates, filepath.Join(home, "bin", "javaw.exe"))
	}
	for _, c := range candidates {
		if info, err := os.Stat(c); err == nil && !info.IsDir() {
			return c
		}
	}
	if path, err := exec.LookPath("javaw.exe"); err == nil {
		return path
	}
	return ""
}

func fail(message string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	text, _ := syscall.UTF16PtrFromString(message)
	title, _ := syscall.UTF16PtrFromString("AmneziaWG")
	const mbIconError = 0x10
	user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(text)), uintptr(unsafe.Pointer(title)), mbIconError)
	os.Exit(1)
}
