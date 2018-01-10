package com.wireguard.android.util;

import android.content.Context;
import android.util.Log;

import com.wireguard.android.Application.ApplicationContext;
import com.wireguard.android.Application.ApplicationScope;
import com.wireguard.android.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;

import javax.inject.Inject;

/**
 * Helper class for running commands as root.
 */

@ApplicationScope
public class RootShell {
    private static final String SU = "su";
    private static final String TAG = "WireGuard/" + RootShell.class.getSimpleName();

    private final String deviceNotRootedMessage;
    private final File localBinaryDir;
    private final File localTemporaryDir;
    private final String preamble;
    private Process process;
    private BufferedReader stderr;
    private OutputStreamWriter stdin;
    private BufferedReader stdout;

    @Inject
    public RootShell(@ApplicationContext final Context context) {
        deviceNotRootedMessage = context.getString(R.string.error_root);
        final File cacheDir = context.getCacheDir();
        localBinaryDir = new File(cacheDir, "bin");
        localTemporaryDir = new File(cacheDir, "tmp");
        preamble = String.format("export PATH=\"%s:$PATH\" TMPDIR='%s'; id -u\n",
                localBinaryDir, localTemporaryDir);
    }

    private static boolean isExecutableInPath(final String name) {
        final String path = System.getenv("PATH");
        if (path == null)
            return false;
        for (final String dir : path.split(":"))
            if (new File(dir, name).canExecute())
                return true;
        return false;
    }

    public synchronized boolean isRunning() {
        try {
            // Throws an exception if the process hasn't finished yet.
            if (process != null)
                process.exitValue();
        } catch (final IllegalThreadStateException ignored) {
            // The existing process is still running.
            return true;
        }
        return false;
    }

    /**
     * Run a command in a root shell.
     *
     * @param output  Lines read from stdout are appended to this list. Pass null if the
     *                output from the shell is not important.
     * @param command Command to run as root.
     * @return The exit value of the command.
     */
    public synchronized int run(final Collection<String> output, final String command)
            throws IOException, NoRootException {
        start();
        final String marker = UUID.randomUUID().toString();
        final String script = "echo " + marker + "; echo " + marker + " >&2; (" + command +
                "); ret=$?; echo " + marker + " $ret; echo " + marker + " $ret >&2\n";
        Log.v(TAG, "executing: " + command);
        stdin.write(script);
        stdin.flush();
        String line;
        int errnoStdout = Integer.MIN_VALUE;
        int errnoStderr = Integer.MAX_VALUE;
        int markersSeen = 0;
        while ((line = stdout.readLine()) != null) {
            if (line.startsWith(marker)) {
                ++markersSeen;
                if (line.length() > marker.length() + 1) {
                    errnoStdout = Integer.valueOf(line.substring(marker.length() + 1));
                    break;
                }
            } else if (markersSeen > 0) {
                if (output != null)
                    output.add(line);
                Log.v(TAG, "stdout: " + line);
            }
        }
        while ((line = stderr.readLine()) != null) {
            if (line.startsWith(marker)) {
                ++markersSeen;
                if (line.length() > marker.length() + 1) {
                    errnoStderr = Integer.valueOf(line.substring(marker.length() + 1));
                    break;
                }
            } else if (markersSeen > 2) {
                Log.v(TAG, "stderr: " + line);
            }
        }
        if (markersSeen != 4)
            throw new IOException("Expected 4 markers, received " + markersSeen);
        if (errnoStdout != errnoStderr)
            throw new IOException("Unable to read exit status");
        Log.v(TAG, "exit: " + errnoStdout);
        return errnoStdout;
    }

    public synchronized void start() throws IOException, NoRootException {
        if (isRunning())
            return;
        if (!localBinaryDir.isDirectory() && !localBinaryDir.mkdirs())
            throw new FileNotFoundException("Could not create local binary directory");
        if (!localTemporaryDir.isDirectory() && !localTemporaryDir.mkdirs())
            throw new FileNotFoundException("Could not create local temporary directory");
        if (!isExecutableInPath(SU))
            throw new NoRootException(deviceNotRootedMessage);
        try {
            final ProcessBuilder builder = new ProcessBuilder().command(SU);
            builder.environment().put("LC_ALL", "C");
            try {
                process = builder.start();
            } catch (final IOException e) {
                // A failure at this stage means the device isn't rooted.
                throw new NoRootException(deviceNotRootedMessage, e);
            }
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8));
            stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(),
                    StandardCharsets.UTF_8));
            stdin.write(preamble);
            stdin.flush();
            // Check that the shell started successfully.
            final String uid = stdout.readLine();
            if (!"0".equals(uid)) {
                Log.w(TAG, "Root check did not return correct UID: " + uid);
                throw new NoRootException(deviceNotRootedMessage);
            }
            if (!isRunning()) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    Log.w(TAG, "Root check returned an error: " + line);
                    if (line.contains("Permission denied"))
                        throw new NoRootException(deviceNotRootedMessage);
                }
                throw new IOException("Shell failed to start: " + process.exitValue());
            }
        } catch (final IOException | NoRootException e) {
            stop();
            throw e;
        }
    }

    public synchronized void stop() throws IOException {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    public static class NoRootException extends Exception {
        public NoRootException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public NoRootException(final String message) {
            super(message);
        }
    }
}