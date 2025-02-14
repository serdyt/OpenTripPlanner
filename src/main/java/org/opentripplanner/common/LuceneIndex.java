package org.opentripplanner.common;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.opentripplanner.model.Stop;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.GraphIndex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opentripplanner.gtfs.GtfsLibrary.convertIdToString;

/**
 * Lucene based index of streets, stops, etc.
 * For reference see:
 * https://svn.apache.org/repos/asf/lucene/dev/trunk/lucene/demo/src/java/org/apache/lucene/demo/IndexFiles.java
 */
public class LuceneIndex {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneIndex.class);

    private Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_47);
    private QueryParser parser = new QueryParser(Version.LUCENE_47, "name", analyzer);
    private GraphIndex graphIndex;
    private File basePath;
    private Directory directory; // the Lucene Directory, not to be confused with a filesystem directory
    private IndexSearcher searcher; // Will be null until index is built.

    /**
     * @param basePath the filesystem location under which to save indexes
     * @param background if true, perform the initial indexing in a background thread, if false block to index
     */
    public LuceneIndex(final GraphIndex graphIndex, File basePath, boolean background) {
        this.graphIndex = graphIndex;
        this.basePath = basePath;
        if (background) {
            new BackgroundIndexer().start();
        } else {
            new BackgroundIndexer().run();
        }
    }

    /**
     * Index stations, stops, intersections, streets, and addresses by name and location.
     */
    private void index() {
        try {
            long startTime = System.currentTimeMillis();
            /* Create or re-open a disk-backed Lucene Directory under the OTP server base filesystem directory. */
            directory = FSDirectory.open(new File(basePath, "lucene"));
            // TODO reuse the index if it exists?
            //directory = new RAMDirectory(); // only a little faster
            IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_47, analyzer).setOpenMode(OpenMode.CREATE);
            final IndexWriter writer = new IndexWriter(directory, config);
            for (Stop stop : graphIndex.stationForId.values()) {
                addStop(writer, stop);
            }
            writer.close();
            long elapsedTime = System.currentTimeMillis() - startTime;
            LOG.info("Built Lucene index in {} msec", elapsedTime);
            // Make the IndexSearcher necessary for querying.
            searcher = new IndexSearcher(DirectoryReader.open(directory));
        } catch (Exception ex) {
            throw new RuntimeException("Lucene indexing failed.", ex);
        }
    }

    private void addStop(IndexWriter iwriter, Stop stop) throws IOException {
        Document doc = new Document();
        doc.add(new TextField("name", stop.getName(), Field.Store.YES));
        if (stop.getCode() != null) {
            doc.add(new StringField("code", stop.getCode(), Field.Store.YES));
        }
        if (stop.getPlatformCode() != null) {
            doc.add(new StringField("platformCode", stop.getPlatformCode(), Field.Store.YES));
        }
        doc.add(new DoubleField("lat", stop.getLat(), Field.Store.YES));
        doc.add(new DoubleField("lon", stop.getLon(), Field.Store.YES));
        doc.add(new StringField("id", convertIdToString(stop.getId()), Field.Store.YES));
        doc.add(new StringField("category", Category.STOP.name(), Field.Store.YES));
        iwriter.addDocument(doc);
    }

    private void addCorner(IndexWriter iwriter, StreetVertex sv) throws IOException {
        String mainStreet = null;
        String crossStreet = null;
        // TODO score based on OSM street type, using intersection nodes instead of vertices.
        for (StreetEdge pse : Iterables.filter(sv.getOutgoing(), StreetEdge.class)) {
            if (mainStreet == null) mainStreet = pse.getName();
            else crossStreet = pse.getName();
        }
        if (mainStreet == null || crossStreet == null) return;
        if (mainStreet.equals(crossStreet)) return;
        Document doc = new Document();
        doc.add(new TextField("name", mainStreet + " & " + crossStreet, Field.Store.YES));
        doc.add(new DoubleField("lat", sv.getLat(), Field.Store.YES));
        doc.add(new DoubleField("lon", sv.getLon(), Field.Store.YES));
        doc.add(new StringField("category", Category.CORNER.name(), Field.Store.YES));
        iwriter.addDocument(doc);
    }

    private class BackgroundIndexer extends Thread {
        @Override
        public void run() {
            LOG.info("Starting background Lucene indexing.");
            index();
        }
    }

    /** Fetch results for the geocoder using the OTP graph for stops and street names
     *
     * @param queryString
     * @param autocomplete Whether we should use the query string to do a prefix match
     * @param stops Search for stops, either by name or stop code
     * @param corners Search for street corners using at least one of the street names
     * @return list of results in in the format expected by GeocoderBuiltin.js in the OTP Leaflet client
     */
    public List<LuceneResult> query (String queryString, boolean autocomplete, boolean stops, boolean corners) {
        /* Turn the query string into a Lucene query.*/
        BooleanQuery query = new BooleanQuery();
        BooleanQuery termQuery = new BooleanQuery();
        if (autocomplete) {
            for (String term : queryString.split(" ")) {
                termQuery.add(new TermQuery(new Term("name", term.toLowerCase())), BooleanClause.Occur.SHOULD);
                termQuery.add(new PrefixQuery(new Term("name", term.toLowerCase())), BooleanClause.Occur.SHOULD);
                // This makes it possible to search for a stop code
                termQuery.add(new TermQuery(new Term("code", term)),
                    BooleanClause.Occur.SHOULD);
            }
        } else {
            List<String> list = new ArrayList<String>();
            Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(queryString);
            while (m.find()) {
                String token = m.group(1);

                // if token is a quoted search phrase
                if (token.startsWith("\"") && token.endsWith("\"")) {
                    PhraseQuery phraseQuery = new PhraseQuery();
                    for (String phraseToken : token.substring(1, token.length() - 1)
                        .split(" ")) {
                        phraseQuery.add(new Term("name", phraseToken.toLowerCase()));
                    }
                    termQuery.add(phraseQuery, BooleanClause.Occur.SHOULD);
                } else { // a regular unquoted search term
                    termQuery.add(new FuzzyQuery(new Term("name", token)),
                        BooleanClause.Occur.SHOULD);

                    // This makes it possible to search for a stop code
                    termQuery.add(new TermQuery(new Term("code", token)),
                        BooleanClause.Occur.SHOULD);
                }
            }
        }

        query.add(termQuery, BooleanClause.Occur.MUST);

        if (stops || corners) {
            BooleanQuery typeQuery = new BooleanQuery();
            if (stops) {
                typeQuery.add(new TermQuery(new Term("category", Category.STOP.name())), BooleanClause.Occur.SHOULD);
            }
            if (corners) {
                typeQuery.add(new TermQuery(new Term("category", Category.CORNER.name())), BooleanClause.Occur.SHOULD);
            }
            query.add(typeQuery, BooleanClause.Occur.MUST);
        }
        List<LuceneResult> result = Lists.newArrayList();
        try {
            TopScoreDocCollector collector = TopScoreDocCollector.create(30, true);
            searcher.search(query, collector);
            ScoreDoc[] docs = collector.topDocs().scoreDocs;
            for (int i = 0; i < docs.length; i++) {
                LuceneResult lr = new LuceneResult();
                Document doc = searcher.doc(docs[i].doc);
                lr.lat = doc.getField("lat").numericValue().doubleValue();
                lr.lng = doc.getField("lon").numericValue().doubleValue();
                String category = doc.getField("category").stringValue().toLowerCase();
                String platformCode;
                if (doc.getField("platformCode") != null){
                    platformCode = "(" + doc.getField("platformCode").stringValue() + ")";
                } else {
                    platformCode = "";
                }
                if (doc.getField("category").stringValue().equals(Category.STOP.name())) {
                    lr.id = doc.getField("id").stringValue();
                }
                String name = doc.getField("name").stringValue();
                if (lr.id != null) {
                    lr.description = name + " " + platformCode + " (" + lr.id + ")";
                } else {
                    lr.description = name + " " + platformCode + " " + category;
                }
                result.add(lr);
            }
        } catch (Exception ex) {
            LOG.error("Error during Lucene search", ex);
        } finally {
            return result;
        }
    }

    /** This class matches the structure of the Geocoder responses expected by the OTP client. */
    public static class LuceneResult {
        public double lat;
        public double lng;
        public String description;
        public String id;
    }

    public static enum Category { STOP, CORNER; }
}

