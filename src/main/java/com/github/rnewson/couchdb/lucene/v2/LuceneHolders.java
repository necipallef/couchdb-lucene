package com.github.rnewson.couchdb.lucene.v2;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Holds important stateful Lucene objects (Writer and Reader) and provides
 * appropriate locking.
 * 
 * @author rnewson
 * 
 */
final class LuceneHolders {

    private static abstract class IndexMappingStrategy {
        abstract Directory map(final String indexName) throws IOException;
    }

    private static class LuceneHolder {

        private final Directory dir;

        private IndexReader reader;

        private final boolean realtime;

        private final IndexWriter writer;

        private LuceneHolder(final Directory dir, final boolean realtime) throws IOException {
            this.dir = dir;
            this.realtime = realtime;
            this.writer = newWriter();
            this.reader = newReader();
            this.reader.incRef();
        }

        private IndexReader newReader() throws IOException {
            if (realtime) {
                return getIndexWriter().getReader();
            }
            return IndexReader.open(dir, true);
        }

        private IndexWriter newWriter() throws IOException {
            final IndexWriter result = new IndexWriter(dir, Constants.ANALYZER, MaxFieldLength.UNLIMITED);
            final LogByteSizeMergePolicy mp = new LogByteSizeMergePolicy(result);
            mp.setMergeFactor(5);
            mp.setMaxMergeMB(1000);
            mp.setUseCompoundFile(false);
            result.setMergePolicy(mp);
            result.setRAMBufferSizeMB(16);
            return result;
        }

        synchronized IndexReader borrowReader() throws IOException {
            reader.incRef();
            return reader;
        }

        synchronized IndexSearcher borrowSearcher() throws IOException {
            final IndexReader reader = borrowReader();
            return new IndexSearcher(reader);
        }

        IndexWriter getIndexWriter() throws IOException {
            return writer;
        }

        void reopenReader() throws IOException {
            final IndexReader oldReader;
            synchronized (this) {
                oldReader = reader;
            }

            final IndexReader newReader = oldReader.reopen();

            if (reader != newReader) {
                synchronized (this) {
                    reader = newReader;
                    oldReader.decRef();
                }
            }
        }

        synchronized void returnReader(final IndexReader reader) throws IOException {
            reader.decRef();
        }

        synchronized void returnSearcher(final IndexSearcher searcher) throws IOException {
            returnReader(searcher.getIndexReader());
        }
    }

    private static class MultiIndexStrategy extends IndexMappingStrategy {

        private final File baseDir;

        private MultiIndexStrategy(final File baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public Directory map(final String indexName) throws IOException {
            return FSDirectory.open(new File(baseDir, indexName));
        }

    }

    private static class SingleIndexStrategy extends IndexMappingStrategy {

        private final File baseDir;

        private SingleIndexStrategy(final File baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public Directory map(final String indexName) throws IOException {
            return FSDirectory.open(baseDir);
        }

    }

    interface ReaderCallback<T> {
        public T callback(final IndexReader reader) throws IOException;
    }

    interface SearcherCallback<T> {
        public T callback(final IndexSearcher searcher) throws IOException;
    }

    interface WriterCallback<T> {
        public T callback(final IndexWriter writer) throws IOException;
    }

    private final Map<String, LuceneHolder> holders = new LinkedHashMap<String, LuceneHolder>();

    private final boolean realtime;

    private final IndexMappingStrategy strategy;

    LuceneHolders(final File baseDir, final boolean realtime) {
        this.realtime = realtime;
        this.strategy = new MultiIndexStrategy(baseDir);
    }

    private synchronized LuceneHolder getHolder(final String indexName) throws IOException {
        LuceneHolder result = holders.get(indexName);
        if (result == null) {
            result = new LuceneHolder(strategy.map(indexName), realtime);
            holders.put(indexName, result);
        }
        return result;
    }

    <T> T withReader(final String indexName, final ReaderCallback<T> callback) throws IOException {
        final LuceneHolder holder = getHolder(indexName);
        final IndexReader reader = holder.borrowReader();
        try {
            return callback.callback(reader);
        } finally {
            holder.returnReader(reader);
        }
    }

    <T> T withSearcher(final String indexName, final SearcherCallback<T> callback) throws IOException {
        final LuceneHolder holder = getHolder(indexName);
        final IndexSearcher searcher = holder.borrowSearcher();
        try {
            return callback.callback(searcher);
        } finally {
            holder.returnSearcher(searcher);
        }
    }

    <T> T withWriter(final String indexName, final WriterCallback<T> callback) throws IOException {
        final LuceneHolder holder = getHolder(indexName);
        final IndexWriter writer = holder.getIndexWriter();
        return callback.callback(writer);
    }

    void reopenReader(final String indexName) throws IOException {
        getHolder(indexName).reopenReader();
    }

}
