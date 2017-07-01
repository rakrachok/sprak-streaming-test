package org.test.pcap4j;

import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.receiver.Receiver;
import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CaptureNetworkReceiver extends Receiver<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(CaptureNetworkReceiver.class);

    private static final String READ_TIMEOUT_KEY
            = CaptureNetworkReceiver.class.getName() + ".readTimeout";
    private static final int READ_TIMEOUT
            = Integer.getInteger(READ_TIMEOUT_KEY, 10); // [ms]

    private static final String SNAPLEN_KEY
            = CaptureNetworkReceiver.class.getName() + ".snaplen";
    private static final int SNAPLEN
            = Integer.getInteger(SNAPLEN_KEY, 65536); // [bytes]

    private static final String BUFFER_SIZE_KEY
            = CaptureNetworkReceiver.class.getName() + ".bufferSize";
    private static final int BUFFER_SIZE
            = Integer.getInteger(BUFFER_SIZE_KEY, 1 * 1024 * 1024); // [bytes]

    private static final String NIF_NAME_KEY
            = CaptureNetworkReceiver.class.getName() + ".nifName";
    private static final String NIF_NAME
            = System.getProperty(NIF_NAME_KEY);

    private ConcurrentMap<String, Thread> localReceivers = new ConcurrentHashMap<>();

    public CaptureNetworkReceiver() {
        super(StorageLevel.MEMORY_AND_DISK_SER_2());
    }

    @Override
    public void onStart() {
        List<PcapNetworkInterface> nifs;
        try {
            nifs = Pcaps.findAllDevs();
        } catch (PcapNativeException e) {
            throw new RuntimeException(e);
        }

        if (nifs == null) {
            stop("Cannot find any NIFs");
            return;
        }

        for (PcapNetworkInterface nif : nifs) {
            Thread t = new Thread(() -> receive(nif));
            localReceivers.put(nif.getName(), t);
        }

        localReceivers.forEach((s, t) -> {
            log.info("Starting a thread for " + s);
            t.start();
        });
    }

    @Override
    public void onStop() {
        localReceivers.forEach((s, t) -> t.interrupt());
    }

    private void receive(PcapNetworkInterface nif) {
        log.info("Starting monitoring NIF:" + nif.getName());
        PcapHandle loHandle;
        try {
            loHandle = nif.openLive(SNAPLEN, PcapNetworkInterface.PromiscuousMode.NONPROMISCUOUS, READ_TIMEOUT);
        } catch (PcapNativeException e) {
            log.error("Error", e);
            stop("Receiver has been stopped by the error.", e);
            return;
        }

        while (true) {
            byte[] rawPacket;
            try {
                rawPacket = loHandle.getNextRawPacket();
            } catch (NotOpenException e) {
                log.error("Error", e);
                stop("Receiver has been stopped by the error.", e);
                return;
            }

            if (rawPacket == null) {
                log.warn("Nothing to read.");
                restart("Nothing to read. The receiver is being restarted");
            } else {
                log.info("Storing bytes: " + rawPacket);
                store(ByteBuffer.wrap(rawPacket));
            }
        }
    }
}
