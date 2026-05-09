package local.example.collectorframework.collectors;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.auto.service.AutoService;
import io.github.xseejx.collectorframework.api.Collector;
import io.github.xseejx.collectorframework.api.CollectorMetadata;
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.CollectorMetadata.ParameterType;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import oshi.SystemInfo;
import oshi.hardware.UsbDevice;

/**
 * USB Device Collector.
 *
 * Recupera la lista flat di tutti i dispositivi USB collegati tramite OSHI
 * (getUsbDevices(false) = lista senza controller, solo dispositivi reali).
 *
 * Per ogni dispositivo espone:
 *   name          → nome prodotto (es. "USB Keyboard", "USB 3.0 Hub")
 *   vendor        → nome produttore (es. "Logitech", "Kingston")
 *   vendorId      → VID esadecimale (es. "046d") — identifica univocamente il produttore
 *   productId     → PID esadecimale (es. "c31c") — identifica il modello
 *   serialNumber  → numero seriale, se disponibile e restituito dal dispositivo
 *   uniqueDeviceId→ ID di sistema (PnPDeviceID su Windows, device node su Linux)
 *   known         → true se vendorId è nella whitelist dei VID noti
 *
 * Alert generati:
 *   [USB] Dispositivo con VendorID sconosciuto  → VID non in whitelist
 *   [USB] Dispositivo senza nome e senza vendor → completamente anonimo
 *   [USB] Troppi dispositivi USB                → oltre MAX_USB_DEVICES
 *
 * Parametri via reflection:
 *   includeAll       → include anche hub USB e controller interni
 *   alertUnknown     → abilita alert per VID non in whitelist (default: true)
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.usb",
    description = "Dispositivi USB collegati: vendor, product, seriale e rilevamento anomalie",
    tags        = {"hardware", "usb", "security"}
)
public class CollectorUSB implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorUSB.class);

    // With reflective modify those values on core
    private boolean includeAll;      // include hub e controller
    private boolean alertUnknown;    // alert per VID sconosciuti (default true)

    // ── Soglie ──────────────────────────────────────────────────────────────
    private static final int MAX_USB_DEVICES = 20;

    /**
     * Whitelist di Vendor ID (VID) USB noti e comuni.
     * I VID sono assegnati dall'USB-IF (usb.org) — ogni produttore ha il suo.
     * Lista rappresentativa dei vendor più diffusi su sistemi desktop/laptop.
     *
     * Formato: stringa esadecimale lowercase a 4 cifre (es. "046d").
     */
    private static final Set<String> KNOWN_VENDOR_IDS = new HashSet<>(Arrays.asList(
        // Periferiche input
        "046d",  // Logitech
        "045e",  // Microsoft
        "04f2",  // Chicony Electronics (tastiere/webcam OEM)
        "0461",  // Primax Electronics (mouse OEM)
        "1532",  // Razer
        "054c",  // Sony
        "04b4",  // Cypress Semiconductor (controller HID comuni)
        "1038",  // SteelSeries
        "046a",  // Cherry
        "04d9",  // Holtek (tastiere economiche)
        "258a",  // SINOWEALTH (periferiche gaming)
        "1b1c",  // Corsair
        "0951",  // Kingston Technology
        // Storage
        "0781",  // SanDisk / Western Digital
        "1058",  // Western Digital
        "0480",  // Toshiba America
        "04e8",  // Samsung
        "0bda",  // Realtek (hub, reader schede)
        "14cd",  // Super Top (hub, adattatori)
        "0bc2",  // Seagate
        "059f",  // LaCie
        "152d",  // JMicron (bridge USB-SATA)
        "067b",  // Prolific Technology (bridge USB-seriale/SATA)
        "174c",  // ASMedia Technology (hub USB 3.x)
        // Audio
        "08bb",  // Texas Instruments PCM (DAC audio)
        "0d8c",  // C-Media Electronics (schede audio USB)
        "1235",  // Focusrite-Novation
        "0763",  // M-Audio
        // Webcam / Video
        "046d",  // Logitech (webcam)
        "0c45",  // Microdia (webcam OEM)
        "05ac",  // Apple
        // Hub e controller
        "0409",  // NEC / Renesas Electronics (hub USB)
        "0451",  // Texas Instruments (hub)
        "05e3",  // Genesys Logic (hub USB)
        "2109",  // VIA Labs (hub USB 3.x)
        "1a40",  // TERMINUS TECHNOLOGY (hub)
        // Smartphone / Tablet (MTP/ADB)
        "18d1",  // Google (Pixel, Nexus, ADB)
        "2717",  // Xiaomi
        "1bbb",  // T&A Mobile Phones (Alcatel)
        "2a45",  // Meizu
        // Stampanti
        "04a9",  // Canon
        "03f0",  // HP
        "04b8",  // Epson
        "04da",  // Panasonic
        // Bluetooth / WiFi dongle
        "0a12",  // Cambridge Silicon Radio (CSR Bluetooth)
        "0cf3",  // Qualcomm Atheros (WiFi)
        "148f",  // Ralink Technology (WiFi)
        "0bda",  // Realtek (WiFi)
        // Programmatori / Debug
        "0403",  // Future Technology Devices (FTDI — USB-seriale)
        "10c4",  // Silicon Labs (CP210x USB-seriale)
        "1a86",  // QinHeng Electronics (CH340 USB-seriale)
        // Smart card / Sicurezza
        "096e",  // Feitian Technologies (smart card)
        "04e6",  // SCM Microsystems (smart card reader)
        "1050",  // Yubico (YubiKey)
        "096e"   // Feitian
    ));

    /**
     * Nomi di dispositivi che indicano hub o controller interni da filtrare
     * se includeAll = false.
     */
    private static final Set<String> HUB_KEYWORDS = new HashSet<>(Arrays.asList(
        "hub", "root hub", "usb root", "host controller", "composite device",
        "controller", "xhci", "ehci", "ohci", "uhci"
    ));

    @Override
    public String getName() { return "hardware.usb"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();

            // false = lista flat (solo dispositivi, senza controller come root node)
            List<UsbDevice> devices = si.getHardware().getUsbDevices(false);

            JSONArray  deviceArray = new JSONArray();
            JSONArray  alertArray  = new JSONArray();

            int anonymousCount = 0;
            int unknownVidCount = 0;

            for (UsbDevice device : devices) {
                String name      = clean(device.getName());
                String vendor    = clean(device.getVendor());
                String vendorId  = clean(device.getVendorId()).toLowerCase();
                String productId = clean(device.getProductId()).toLowerCase();
                String serial    = clean(device.getSerialNumber());
                String uniqueId  = clean(device.getUniqueDeviceId());

                // Filtro hub interni se includeAll = false
                if (!includeAll && isHub(name)) continue;

                boolean hasVendorId = !vendorId.isEmpty() && !vendorId.equals("0000");
                boolean knownVid    = hasVendorId && KNOWN_VENDOR_IDS.contains(vendorId);
                boolean hasName     = !name.isEmpty();
                boolean hasVendor   = !vendor.isEmpty();

                JSONObject obj = new JSONObject();
                obj.put("name",          hasName    ? name      : "Unknown");
                obj.put("vendor",        hasVendor  ? vendor    : "Unknown");
                obj.put("vendorId",      hasVendorId ? vendorId : "N/A");
                obj.put("productId",     !productId.isEmpty() ? productId : "N/A");
                obj.put("serialNumber",  !serial.isEmpty()    ? serial    : "N/A");
                obj.put("uniqueDeviceId", !uniqueId.isEmpty() ? uniqueId  : "N/A");
                obj.put("known",         knownVid);

                deviceArray.add(obj);

                // ── Raccolta per alert ────────────────────────────────────────
                if (!hasName && !hasVendor && !hasVendorId) {
                    anonymousCount++;
                }
                if (alertUnknown && hasVendorId && !knownVid) {
                    unknownVidCount++;
                }
            }

            // ── Generazione alert ─────────────────────────────────────────────

            // Troppi dispositivi
            if (deviceArray.size() > MAX_USB_DEVICES) {
                alertArray.add(String.format(
                    "[USB] Numero elevato di dispositivi USB: %d (soglia: %d) — verificare presenza di hub non autorizzati",
                    deviceArray.size(), MAX_USB_DEVICES));
            }

            // VID sconosciuti
            if (unknownVidCount > 0) {
                // Dettaglio per ogni dispositivo sconosciuto
                for (Object obj : deviceArray) {
                    JSONObject d = (JSONObject) obj;
                    if (Boolean.FALSE.equals(d.get("known"))) {
                        alertArray.add(String.format(
                            "[USB] Dispositivo con VendorID sconosciuto: \"%s\" (VID: %s, PID: %s) — dispositivo non in whitelist vendor noti",
                            d.get("name"), d.get("vendorId"), d.get("productId")));
                    }
                }
            }

            // Dispositivi completamente anonimi
            if (anonymousCount > 0) {
                alertArray.add(String.format(
                    "[USB] %d dispositivo/i USB senza nome, vendor o VendorID — possibile dispositivo non standard o malevolo",
                    anonymousCount));
            }

            // ── Output ────────────────────────────────────────────────────────
            JSONObject result = new JSONObject();
            result.put("usbCount",   deviceArray.size());
            result.put("devices",    deviceArray);
            result.put("alertCount", alertArray.size());
            result.put("alerts",     alertArray);

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting USB device information", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Pulisce una stringa OSHI: trim + rimuove null. */
    private String clean(String s) {
        return (s == null) ? "" : s.trim();
    }

    /** Restituisce true se il nome del dispositivo indica un hub o controller interno. */
    private boolean isHub(String name) {
        if (name.isEmpty()) return false;
        String lower = name.toLowerCase();
        return HUB_KEYWORDS.stream().anyMatch(lower::contains);
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll",    Boolean.class,   // include hub e controller
            "alertUnknown",  Boolean.class    // alert per VID non in whitelist
        );
    }

    public static void main(String[] args) {
        CollectorUSB collector = new CollectorUSB();
        collector.includeAll   = false;
        collector.alertUnknown = true;

        System.out.println("=== USB Collector Test ===");
        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}