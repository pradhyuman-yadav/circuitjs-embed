package com.lushprojects.circuitjs1.client;

import com.google.gwt.core.client.JsonUtils;

// JavaScript interface to the simulator, published as window.CircuitJS1.
class CircuitAPI {
    private static final int MAX_INFO_LINES = 10;
    private static CirSim sim;

    static void install(CirSim simulator) {
	sim = simulator;
	publish();
    }

    private static String load(String text) {
	sim.readCircuit(text);
	sim.unsavedChanges = false;
	return "{\"skippedLines\":" + sim.readErrors + "}";
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
	json.append(",\"error\":").append(text(sim.stopMessage));
	json.append(",\"elements\":[");
	boolean first = true;
	for (int i = 0; i != sim.elmList.size(); i++) {
	    CircuitElm elm = sim.getElm(i);
	    if (elm instanceof WireElm)
		continue;
	    if (!first)
		json.append(',');
	    first = false;
	    appendElement(json, i, elm);
	}
	return json.append("]}").toString();
    }

    private static void appendElement(StringBuilder json, int index, CircuitElm elm) {
	String info[] = new String[MAX_INFO_LINES];
	try {
	    elm.getInfo(info);
	} catch (Exception e) {
	    info[0] = "unknown";
	}
	json.append("{\"index\":").append(index);
	json.append(",\"name\":").append(text(info[0]));
	json.append(",\"voltage\":").append(number(elm.getVoltageDiff()));
	json.append(",\"current\":").append(number(elm.getCurrent()));
	json.append(",\"power\":").append(number(elm.getPower()));
	json.append(",\"info\":[");
	for (int i = 1; i != MAX_INFO_LINES && info[i] != null; i++) {
	    if (i > 1)
		json.append(',');
	    json.append(text(info[i]));
	}
	json.append("]}");
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
	    })
	};
	if ($wnd.CircuitJSEmbed && $wnd.CircuitJSEmbed.onReady)
	    $wnd.CircuitJSEmbed.onReady($wnd.CircuitJS1);
    }-*/;
}
