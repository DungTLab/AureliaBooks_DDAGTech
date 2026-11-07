package com.ddagtech.aureliabooks.config;

/** JDK-only synthetic pipe fixture. Receives synthetic bytes only on stdin. */
public final class RuntimeConfigurationSyntheticChild {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("hang")) { Thread.sleep(30_000); return; }
        byte[] payload = System.in.readAllBytes();
        if (args[0].equals("nonzero") || args[0].equals("stderr-hang")) {
            System.err.write(payload); System.err.flush();
            if (args[0].equals("nonzero")) System.exit(7);
            Thread.sleep(30_000); return;
        }
        var stream = args[0].equals("stdout") ? System.out : System.err;
        stream.write(payload);
        byte[] block = new byte[8192];
        while (true) { stream.write(block); stream.flush(); }
    }
}
