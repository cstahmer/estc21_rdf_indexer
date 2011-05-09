/** 
 *  Copyright 2011 Applied Research in Patacriticism and the University of Virginia
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 **/
package org.nines;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.IOUtils;
import org.openrdf.model.Statement;
import org.openrdf.rio.ParseErrorListener;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.rdfxml.RDFXMLParser;

/**
 * RDF document parser that only handles full text fields. It will
 * spider out to external sites, scrape text and write it to the
 * solr raw text directory
 * 
 * @author loufoster
 *
 */
public class RdfTextSpider implements RDFHandler {

    private ErrorReport errorReport;
    private RDFIndexerConfig config;
    private HttpClient httpClient;
    
    public RdfTextSpider(RDFIndexerConfig config, ErrorReport errorReport) {
        this.config = config;
        this.errorReport = errorReport;
        this.httpClient = new HttpClient();
    }
    
    /**
     * Parse the RDF file for the text field. Spider the URL specifed and
     * write text from this site to the raw text files.
     * 
     * @param file
     */
    public void spider( final File file ) {
        RDFXMLParser parser = new RDFXMLParser();
        parser.setRDFHandler( this );
        parser.setParseErrorListener( new ParseListener(file, errorReport));
        parser.setVerifyData(true);
        parser.setStopAtFirstError(false);

        try {
            
            InputStreamReader is = new InputStreamReader(new FileInputStream(file) );
            parser.parse( is, "http://foo/" + file.getName());

        } catch (RDFParseException e) {
            errorReport.addError(new IndexerError(file.getName(), "", "Parse Error on Line " + e.getLineNumber() + ": "
                    + e.getMessage()));
        } catch (RDFHandlerException e) {
            errorReport.addError(new IndexerError(file.getName(), "", "StatementHandler Exception: " + e.getMessage()));
        } catch (Exception e) {
            errorReport.addError(new IndexerError(file.getName(), "", "RDF Parser Error: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * Handle RDF statements. This only cares about TEXT statements and will scrape
     * text from the URL specified.
     */
    public void handleStatement(Statement statement) throws RDFHandlerException {
        
        String predicate = statement.getPredicate().stringValue().trim();
        String object = statement.getObject().stringValue().trim();

        // if the object of the triple is blank, skip it, it is nothing worth indexing
        if (object == null || object.length() == 0) {
            return;
        }
        
        // only care about TEXT
        if ("http://www.collex.org/schema#text".equals(predicate) == false ) {
            return;
        }
        
        // only care if it looks like a URL and is not a PDF
        if (object.startsWith("http://") ) {
            if (object.endsWith(".pdf") || object.endsWith(".PDF")) {
                return;
            } 
            getRawText(object);  
        }
    }
    
    /**
     * Get the full text from an external site an write it untouched to the
     * rawtext area of the solr sources. If any errors occur,leave any
     * prior versions of the rawtext untouched, log the errors and return
     * @param urlString
     * @return
     */
    private void getRawText(String urlString) {

        String rawFile = urlString.replaceAll("/", "SL");;
        rawFile = rawFile.replace(":", "CL");
        rawFile = rawFile.replace("?", "QU");
        rawFile = rawFile.replace("=", "EQ");
        rawFile = rawFile.replace("&", "AMP");
        String rawRoot = this.config.getRawTextRoot();
        rawRoot = rawRoot + SolrClient.safeCore( this.config.archiveName);
        File urlFile = new File(rawRoot + "/"+ rawFile );
        
        // scrape the content from remote host...
        String content = "";
        try {
            content = scrapeExternalText(urlString);
        } catch (IOException e) {
            this.errorReport.addError(
                new IndexerError( "", urlString, "Unable to create get external text: "+e.toString()));
            return;
        }        
               
        // At this point, we have new data. Delete the old - this does
        // nothing if the file does not yet exist
        urlFile.delete();

        // Make sure that the directory structure leadign up 
        // to the detination file exists
        if ( urlFile.getParentFile().exists() == false) {
            if ( urlFile.getParentFile().mkdirs() == false ) {
                this.errorReport.addError(
                    new IndexerError(urlFile.toString(), urlString, "Unable to create raw text file"));
                return;
            }
        }
            
        // dump the content to the file
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(urlFile), "UTF-8");
            out.write(content);
            out.close();
        } catch (IOException e) {
            this.errorReport.addError(
                new IndexerError(urlFile.toString(), urlString, "Unable to create get external text: "+e.toString()));
        }        
    }
    
    private String scrapeExternalText(String url) throws IOException {
        GetMethod get = new GetMethod(url);
        int result;
        try {
            result = this.httpClient.executeMethod(get);
            if (result != 200) {
                throw new IOException(result + " code returned for URL: " + url);
            }
            return IOUtils.toString( get.getResponseBodyAsStream(), "UTF-8" );
        } catch (IOException e ) {
            throw e; // just rethrow it
        } finally {
            get.releaseConnection();
        }
    }

    public void startRDF() throws RDFHandlerException {
        // NO-OP
    }

    public void endRDF() throws RDFHandlerException {
        // NO-OP
    }

    public void handleNamespace(String prefix, String uri) throws RDFHandlerException {
        // NO-OP
    }

    public void handleComment(String comment) throws RDFHandlerException {
        // NO-OP
    }
    
    /**
     * Listen for parse errors and write them to the error report
     * @author loufoster
     *
     */
    private static final class ParseListener implements ParseErrorListener {

        private ErrorReport errorReport;
        private File file;
        
        ParseListener(File file, ErrorReport errorReport ) {
            this.errorReport   = errorReport;
            this.file = file;
        }
        public void warning(String msg, int lineNo, int colNo) {
            this.errorReport.addError(new IndexerError(file.getName(), "", 
                "Parse warning at line "+lineNo+", col "+colNo+" : " + msg));   
        }

        public void error(String msg, int lineNo, int colNo) {
            this.errorReport.addError(new IndexerError(file.getName(), "", 
                "Parse error at line "+lineNo+", col "+colNo+" : " + msg)); 
        }

        public void fatalError(String msg, int lineNo, int colNo) {
            this.errorReport.addError(new IndexerError(file.getName(), "", 
                "FATAL PARSE ERROR at line "+lineNo+", col "+colNo+" : " + msg)); 
        }
        
    }

}
