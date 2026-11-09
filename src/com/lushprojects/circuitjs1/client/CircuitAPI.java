package com.lushprojects.circuitjs1.client;

import com.google.gwt.core.client.JsonUtils;
import java.util.ArrayList;
import java.util.List;

// JavaScript interface to the simulator, published as window.CircuitJS1.
class CircuitAPI {
    private static final int MAX_INFO_LINES = 10;
    private static final int MIN_SAMPLES = 2;
    private static final int MAX_SAMPLES = 5000;
    private static final long SIMULATE_TIME_LIMIT_MS = 20000;
    private static CirSim sim;

    static void install(CirSim simulator) {
	sim = simulator;
	publish();
    }

    private static String load(String text) {
	readAndAnalyze(text);
	sim.setSimRunning(true);
	return "{\"skippedLines\":" + sim.readErrors + ",\"error\":" + text(sim.stopMessage) + "}";
    }

    private static void readAndAnalyze(String text) {
	// The circuit format treats + as a separator, which would split 1e+3.
	sim.readCircuit(text.replaceAll("([0-9.])[eE]\\+([0-9])", "$1e$2"));
	sim.unsavedChanges = false;
	sim.stopMessage = null;
	analyzeIfNeeded();
    }

    // Voltage, current and power fields only describe parts with at most two
    // terminals. Other parts report through their post voltages and info text.
    private static boolean hasSingleBranch(CircuitElm elm) {
	return elm.getPostCount() <= 2 && !(elm instanceof CompositeElm) && !(elm instanceof ChipElm);
    }

    private static String branchValue(CircuitElm elm, double value) {
	return hasSingleBranch(elm) ? number(value) : "null";
    }

    private static void analyzeIfNeeded() {
	if (sim.analyzeFlag) {
	    sim.analyzeCircuit();
	    sim.analyzeFlag = false;
	}
    }

    private static String exportCircuit() {
	return sim.dumpCircuit();
    }

    private static void setRunning(boolean running) {
	sim.setSimRunning(running);
    }

    private static String measure() {
	StringBuilder json = new StringBuilder();
	json.append("{\"time\":").append(number(sim.t));
	json.append(",\"running\":").append(sim.simIsRunning());
	json.append(",\"skippedLines\":").append(sim.readErrors);
	json.append(",\"error\":").append(text(sim.stopMessage));
	json.append(",\"elements\":[");
	List<Integer> indexes = componentIndexes();
	for (int i = 0; i != indexes.size(); i++) {
	    if (i > 0)
		json.append(',');
	    appendElement(json, indexes.get(i));
	}
	return json.append("]}").toString();
    }

    private static List<Integer> componentIndexes() {
	List<Integer> indexes = new ArrayList<Integer>();
	for (int i = 0; i != sim.elmList.size(); i++)
	    if (!(sim.getElm(i) instanceof WireElm))
		indexes.add(i);
	return indexes;
    }

    private static void appendElement(StringBuilder json, int index) {
	CircuitElm elm = sim.getElm(index);
	String info[] = new String[MAX_INFO_LINES];
	try {
	    elm.getInfo(info);
	} catch (Exception e) {
	    info[0] = "unknown";
	}
	json.append("{\"index\":").append(index);
	json.append(",\"name\":").append(text(info[0]));
	json.append(",\"dump\":").append(text(elm.dump()));
	json.append(",\"voltage\":").append(branchValue(elm, elm.getVoltageDiff()));
	json.append(",\"current\":").append(branchValue(elm, elm.getCurrent()));
	json.append(",\"power\":").append(branchValue(elm, elm.getPower()));
	json.append(",\"posts\":[");
	for (int i = 0; i != elm.getPostCount(); i++) {
	    if (i > 0)
		json.append(',');
	    json.append(number(elm.getPostVoltage(i)));
	}
	json.append("],\"info\":[");
	for (int i = 1; i != MAX_INFO_LINES && info[i] != null; i++) {
	    if (i > 1)
		json.append(',');
	    json.append(text(info[i]));
	}
	json.append("]}");
    }

    // Runs the circuit for a span of simulated time, as fast as possible, and
    // returns evenly spaced samples. Loads the circuit first when one is given.
    private static String simulate(String circuit, double duration, int samples) {
	if (Double.isNaN(duration) || Double.isInfinite(duration) || duration <= 0)
	    return "{\"error\":\"duration must be a positive number of seconds\"}";
	int count = Math.max(MIN_SAMPLES, Math.min(MAX_SAMPLES, samples));
	int skippedLines = 0;
	if (circuit != null) {
	    readAndAnalyze(circuit);
	    skippedLines = sim.readErrors;
	} else {
	    analyzeIfNeeded();
	}

	List<Integer> indexes = componentIndexes();
	Series series = new Series(indexes);
	boolean truncated = false;
	if (sim.stopMessage == null) {
	    sim.setupScopes();
	    double start = sim.t;
	    double interval = duration / (count - 1);
	    long deadline = System.currentTimeMillis() + SIMULATE_TIME_LIMIT_MS;
	    // Node voltages are not known until the first step has been solved.
	    if (start == 0)
		sim.runUntil(0, deadline);
	    if (sim.stopMessage == null)
		series.record();
	    for (int i = 1; i != count && sim.stopMessage == null; i++) {
		if (System.currentTimeMillis() > deadline) {
		    truncated = true;
		    break;
		}
		double target = start + i * interval;
		sim.runUntil(target, deadline);
		if (sim.stopMessage != null)
		    break;
		series.record();
		if (sim.t < target) {
		    truncated = true;
		    break;
		}
	    }
	}
	return "{\"skippedLines\":" + skippedLines + ",\"truncated\":" + truncated +
		",\"error\":" + text(sim.stopMessage) + "," + series.toJsonFields() + "}";
    }

    // Time-stamped voltages and currents for each component.
    private static class Series {
	private final List<Integer> indexes;
	private final StringBuilder time = new StringBuilder();
	private final StringBuilder voltage[];
	private final StringBuilder current[];
	private final StringBuilder posts[];
	private boolean empty = true;

	Series(List<Integer> indexes) {
	    this.indexes = indexes;
	    voltage = new StringBuilder[indexes.size()];
	    current = new StringBuilder[indexes.size()];
	    posts = new StringBuilder[indexes.size()];
	    for (int i = 0; i != indexes.size(); i++) {
		voltage[i] = new StringBuilder();
		current[i] = new StringBuilder();
		posts[i] = new StringBuilder();
	    }
	}

	void record() {
	    String separator = empty ? "" : ",";
	    time.append(separator).append(number(sim.t));
	    for (int i = 0; i != indexes.size(); i++) {
		CircuitElm elm = sim.getElm(indexes.get(i));
		voltage[i].append(separator).append(branchValue(elm, elm.getVoltageDiff()));
		current[i].append(separator).append(branchValue(elm, elm.getCurrent()));
		posts[i].append(separator).append('[');
		for (int p = 0; p != elm.getPostCount(); p++) {
		    if (p > 0)
			posts[i].append(',');
		    posts[i].append(number(elm.getPostVoltage(p)));
		}
		posts[i].append(']');
	    }
	    empty = false;
	}

	String toJsonFields() {
	    StringBuilder json = new StringBuilder("\"time\":[").append(time).append("],\"elements\":[");
	    for (int i = 0; i != indexes.size(); i++) {
		if (i > 0)
		    json.append(',');
		CircuitElm elm = sim.getElm(indexes.get(i));
		json.append("{\"index\":").append(indexes.get(i));
		json.append(",\"dump\":").append(text(elm.dump()));
		json.append(",\"voltage\":[").append(voltage[i]).append(']');
		json.append(",\"current\":[").append(current[i]).append(']');
		json.append(",\"posts\":[").append(posts[i]).append("]}");
	    }
	    return json.append(']').toString();
	}
    }

    private static String number(double value) {
	return Double.isNaN(value) || Double.isInfinite(value) ? "null" : String.valueOf(value);
    }

    private static String text(String value) {
	return value == null ? "null" : JsonUtils.escapeValue(value);
    }

    private static native void publish() /*-{
	$wnd.CircuitJS1 = {
	    load: $entry(function(text) {
		return JSON.parse(@com.lushprojects.circuitjs1.client.CircuitAPI::load(Ljava/lang/String;)(String(text)));
	    }),
	    exportCircuit: $entry(function() {
		return @com.lushprojects.circuitjs1.client.CircuitAPI::exportCircuit()();
	    }),
	    measure: $entry(function() {
		return JSON.parse(@com.lushprojects.circuitjs1.client.CircuitAPI::measure()());
	    }),
	    setRunning: $entry(function(running) {
		@com.lushprojects.circuitjs1.client.CircuitAPI::setRunning(Z)(!!running);
	    }),
	    simulate: $entry(function(options) {
		var circuit = options.circuit == null ? null : String(options.circuit);
		var samples = options.samples == null ? 200 : options.samples | 0;
		return JSON.parse(@com.lushprojects.circuitjs1.client.CircuitAPI::simulate(Ljava/lang/String;DI)(circuit, Number(options.duration), samples));
	    })
	};
	if ($wnd.CircuitJSEmbed && $wnd.CircuitJSEmbed.onReady)
	    $wnd.CircuitJSEmbed.onReady($wnd.CircuitJS1);
    }-*/;
}
