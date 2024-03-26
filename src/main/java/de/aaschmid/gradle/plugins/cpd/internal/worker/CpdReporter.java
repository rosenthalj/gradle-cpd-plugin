package de.aaschmid.gradle.plugins.cpd.internal.worker;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;

import de.aaschmid.gradle.plugins.cpd.internal.worker.CpdWorkParameters.Report;
import de.aaschmid.gradle.plugins.cpd.internal.worker.CpdWorkParameters.Report.Xml;
import net.sourceforge.pmd.cpd.CPDReport;
import net.sourceforge.pmd.cpd.CSVRenderer;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.cpd.SimpleRenderer;
import net.sourceforge.pmd.cpd.VSRenderer;
import net.sourceforge.pmd.cpd.XMLRenderer;
import net.sourceforge.pmd.cpd.renderer.CPDRenderer;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

class CpdReporter {

    private static final Logger logger = Logging.getLogger(CpdReporter.class);

    void generate(List<Report> reports, List<Match> matches) {
        if (logger.isInfoEnabled()) {
            logger.info("Generating reports");
        }
        for (Report report : reports) {
            CPDRenderer renderer = createRendererFor(report);
            try (FileWriter fileWriter = new FileWriter(report.getDestination())) {

                ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
                try {
                    // Workaround for Gradle Worker API using special class loader which Xerces dynamic implementation loading does not like
                    Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

                    renderer.render(matches.iterator(), fileWriter);
                } finally {
                    Thread.currentThread().setContextClassLoader(previousContextClassLoader);
                }
            } catch (IOException e) {
                throw new GradleException(e.getMessage(), e);
            }
        }
    }

    /**
     * Note: This cannot be implemented in {@link Report} subclasses because they must be independent of {@link
     * CPDRenderer} because only worker classloader knows about PMD / CPD library.
     *
     * @param report the configured reports used
     * @return a full configured {@link CPDRenderer} to generate a CPD single file reports.
     */
    CPDRenderer createRendererFor(Report report) {
        if (report instanceof Report.Csv) {
            char separator = ((Report.Csv) report).getSeparator();
            boolean lineCountPerFile = !((Report.Csv) report).isIncludeLineCount();

            if (logger.isDebugEnabled()) {
                logger.debug("Creating renderer to generate CSV file separated by '{}'{}.", separator,
                        lineCountPerFile ? " with line count per file" : "");
            }
            return new CSVRenderer(separator, lineCountPerFile);

        } else if (report instanceof Report.Text) {
            String lineSeparator = ((Report.Text) report).getLineSeparator();
            boolean trimLeadingCommonSourceWhitespaces = ((Report.Text) report).isTrimLeadingCommonSourceWhitespaces();

            if (logger.isDebugEnabled()) {
                logger.debug("Creating renderer to generate simple text file separated by '{}' and trimmed '{}'.", lineSeparator, trimLeadingCommonSourceWhitespaces);
            }
            SimpleRenderer result = new SimpleRenderer(lineSeparator);
            setTrimLeadingWhitespacesByReflection(result, trimLeadingCommonSourceWhitespaces);
            return result;

        } else if (report instanceof Report.Vs) {
            return new LocalVSRenderer();

        } else if (report instanceof Report.Xml) {
            String encoding = ((Xml) report).getEncoding();
            if (logger.isDebugEnabled()) {
                logger.debug("Creating XML renderer to generate with encoding '{}'.", encoding);
            }
            return new XMLRenderer(encoding);
        }
        throw new GradleException(String.format("Cannot create reports for unsupported type '%s'.", report.getClass()));
    }
    
    public static class LocalVSRenderer implements CPDRenderer {
	    public void render(Iterator<Match> matches, Writer writer) throws IOException {
	    	List<Match> matchList = new ArrayList<>();
	    	while(matches.hasNext()) {
	    		matchList.add(matches.next());
	    	}
	    	Constructor<?> constructor[]  = CPDReport.class.getDeclaredConstructors();
	    	constructor[0].setAccessible(true);
	    	CPDReport report;
	        if (logger.isDebugEnabled()) {
	            logger.debug("Try initializing private constuctor of CPDReport reflection.");
	        }
	        try {
	        	report= (CPDReport)constructor[0].newInstance(null, matchList, new HashMap<>());
	        } catch (RuntimeException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
	            if (logger.isWarnEnabled()) {
	                logger.warn("Construction by reflection failed", e);
	            }
                throw new RuntimeException(e);
	        }
	        getVSRenderer().render(report, writer);
	    }
	    public VSRenderer getVSRenderer() {
	    	return new VSRenderer();
	    }
    }

    /**
     * Also set second field to trim leading whitespaces.
     * <p/>
     * <i>Information:</i> Use reflection because neither proper constructor for setting both fields nor setter are
     * available.
     */
    private void setTrimLeadingWhitespacesByReflection(CPDRenderer result, boolean trimLeadingCommonSourceWhitespaces) {
        String fieldName = "trimLeadingWhitespace";
        if (logger.isDebugEnabled()) {
            logger.debug("Try setting '{}' field to '{}' for '{}' by reflection.", fieldName, trimLeadingCommonSourceWhitespaces, result);
        }
        try {
            Field field = SimpleRenderer.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(result, trimLeadingCommonSourceWhitespaces);

        } catch (Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn(String.format("Could not set field '%s' on '%s' by reflection.", result.getClass(), fieldName), e);
            }
        }
    }
}
