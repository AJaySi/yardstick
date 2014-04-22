/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.yardstick.probes;

import org.yardstick.*;
import org.yardstick.util.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * Probe that gathers statistics generated by Linux 'dstat' command.
 */
public class DStatProbe implements BenchmarkProbe {
    /** */
    private static final String PATH = "benchmark.probe.dstat.path";

    /** */
    private static final String OPTS = "benchmark.probe.dstat.opts";

    /** */
    private static final int DEFAULT_INTERVAL_IN_SECS = 1;

    /** */
    private static final String DEFAULT_PATH = "dstat";

    /** */
    private static final String DEFAULT_OPTS = "-m --all --noheaders --noupdate " + DEFAULT_INTERVAL_IN_SECS;

    /** */
    private static final String FIRST_LINE_RE =
        "^\\W*\\w*-*memory-usage-* -*total-cpu-usage-* -*dsk/total-* -*net/total-* -*paging-* -*system-*\\s*$";

    /** */
    private static final Pattern FIRST_LINE = Pattern.compile(FIRST_LINE_RE);

    /** */
    private static final String HEADER_LINE_RE = "^\\s*used\\s+buff\\s+cach\\s+free\\s*\\Q|\\E\\s*usr\\s+sys\\s+idl" +
        "\\s+wai\\s+hiq\\s+siq\\s*\\Q|\\E\\s*read\\s+writ\\s*\\Q|\\E\\s*recv\\s+send\\s*\\Q|\\E\\s*in\\s+out" +
        "\\s*\\Q|\\E\\s*int\\s+csw\\s*$";

    /** */
    private static final Pattern HEADER_LINE = Pattern.compile(HEADER_LINE_RE);

    /** */
    private static final Pattern VALUES_PAT;

    static {
        int numFields = 18;

        StringBuilder sb = new StringBuilder("^\\s*");

        for (int i = 0; i < numFields; i++) {
            sb.append("(\\d*\\.\\d+\\w?|\\d+\\w?)");

            if (i < numFields - 1) {
                if (i == 3 || i == 9 || i == 11 || i == 13 || i == 15)
                    sb.append("\\s*\\Q|\\E\\s*");
                else
                    sb.append("\\s+");
            }
            else
                sb.append("\\s*");
        }

        sb.append("\\s*$");

        VALUES_PAT = Pattern.compile(sb.toString());
    }

    /** */
    private BenchmarkConfiguration cfg;

    /** */
    private BenchmarkProcessLauncher proc;

    /** Collected points. */
    private Collection<BenchmarkProbePoint> collected = new ArrayList<>();

    /** {@inheritDoc} */
    @Override public void start(BenchmarkConfiguration cfg) throws Exception {
        this.cfg = cfg;

        BenchmarkClosure<String> c = new BenchmarkClosure<String>() {
            private final AtomicInteger lineNum = new AtomicInteger(0);

            @Override public void apply(String s) {
                parseLine(lineNum.getAndIncrement(), s);
            }
        };

        proc = new BenchmarkProcessLauncher();

        Collection<String> cmdParams = new ArrayList<>();

        cmdParams.add(path(cfg));
        cmdParams.addAll(opts(cfg));

        String execCmd = cmdParams.toString().replaceAll(",|\\[|\\]", "");

        try {
            proc.exec(cmdParams, Collections.<String, String>emptyMap(), c);

            cfg.output().println(this.getClass().getSimpleName() + " is started. Command: '" + execCmd + "'.");
        }
        catch (Exception e) {
            cfg.error().println("Can not start '" + execCmd + "' process due to exception: '" + e.getMessage() + "'.");
        }
    }

    /** {@inheritDoc} */
    @Override public void stop() throws Exception {
        if (proc != null) {
            proc.shutdown(false);

            cfg.output().println(this.getClass().getSimpleName() + " is stopped.");
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<String> metaInfo() {
        return Arrays.asList("Time, ms", "memory used", "memory buff", "memory cach", "memory free", "cpu usr",
            "cpu sys", "cpu idl", "cpu wai", "cpu hiq", "cpu siq", "dsk read", "dsk writ", "net recv", "net send",
            "paging in", "paging out", "system int", "system csw");
    }

    /** {@inheritDoc} */
    @Override public synchronized Collection<BenchmarkProbePoint> points() {
        Collection<BenchmarkProbePoint> ret = collected;

        collected = new ArrayList<>(ret.size() + 5);

        return ret;
    }

    /**
     * @param pnt Probe point.
     */
    private synchronized void collectPoint(BenchmarkProbePoint pnt) {
        collected.add(pnt);
    }

    /**
     * @param lineNum Line number.
     * @param line Line to parse.
     */
    private void parseLine(int lineNum, String line) {
        if (lineNum == 0) {
            Matcher m = FIRST_LINE.matcher(line);

            if (!m.matches())
                cfg.output().println("WARNING: Unexpected first line: '" + line + "'.");
        }
        else if (lineNum == 1) {
            Matcher m = HEADER_LINE.matcher(line);

            if (!m.matches())
                cfg.output().println("ERROR: Header line does not match expected header " +
                    "[exp=" + HEADER_LINE + ", act=" + line + "].");
        }
        else {
            Matcher m = VALUES_PAT.matcher(line);

            if (m.matches()) {
                try {
                    BenchmarkProbePoint pnt = new BenchmarkProbePoint(System.currentTimeMillis(),
                        new double[] {
                            parseValueWithUnit(m.group(1)) / 1024, parseValueWithUnit(m.group(2)) / 1024,
                            parseValueWithUnit(m.group(3)) / 1024, parseValueWithUnit(m.group(4)) / 1024,
                            parseValueWithUnit(m.group(5)), parseValueWithUnit(m.group(6)),
                            parseValueWithUnit(m.group(7)), parseValueWithUnit(m.group(8)),
                            parseValueWithUnit(m.group(9)), parseValueWithUnit(m.group(10)),
                            parseValueWithUnit(m.group(11)), parseValueWithUnit(m.group(12)),
                            parseValueWithUnit(m.group(13)), parseValueWithUnit(m.group(14)),
                            parseValueWithUnit(m.group(15)), parseValueWithUnit(m.group(16)),
                            parseValueWithUnit(m.group(17)), parseValueWithUnit(m.group(18)),
                        });

                    collectPoint(pnt);
                }
                catch (NumberFormatException e) {
                    cfg.output().println("ERROR: Can't parse line '" + line + "' due to exception: '" +
                        e.getMessage() + "'.");
                }
            }
            else
                cfg.output().println("ERROR: Can't parse line: '" + line + "'.");
        }
    }

    /**
     * @param val Value.
     * @return Parsed value.
     */
    private static double parseValueWithUnit(String val) {
        if (val.isEmpty())
            throw new NumberFormatException("Value is empty.");

        char last = val.charAt(val.length() - 1);

        if (Character.isDigit(last))
            return Double.parseDouble(val);

        int multiplier;

        if (last == 'B')
            multiplier = 1;
        else if (last == 'k')
            multiplier = 1024;
        else if (last == 'M')
            multiplier = 1048576;
        else if (last == 'G')
            multiplier = 1073741824;
        else
            throw new NumberFormatException("Unknown '" + last + "' unit of measure for value '" + val + "'.");

        return Double.parseDouble(val.substring(0, val.length() - 1)) * multiplier;
    }

    /**
     * @param cfg Config.
     * @return Path to dstat executable.
     */
    private static String path(BenchmarkConfiguration cfg) {
        String res = cfg.customProperties().get(PATH);

        return res == null || res.isEmpty() ? DEFAULT_PATH : res;
    }

    /**
     * @param cfg Config.
     * @return Options of dstat.
     */
    private static Collection<String> opts(BenchmarkConfiguration cfg) {
        String res = cfg.customProperties().get(OPTS);

        res = res == null || res.isEmpty() ? DEFAULT_OPTS : res;

        return Arrays.asList(res.split("\\s+"));
    }
}