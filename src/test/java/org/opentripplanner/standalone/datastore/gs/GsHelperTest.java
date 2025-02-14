package org.opentripplanner.standalone.datastore.gs;

import com.google.cloud.storage.BlobId;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GsHelperTest {

    @Test
    public void toBlobId() throws URISyntaxException {
        BlobId blobId = GsHelper.toBlobId(new URI("gs://nalle/puh"));
        assertEquals("nalle", blobId.getBucket());
        assertEquals("puh", blobId.getName());
    }

    @Test
    public void toBlobIdWithInvalidURL() throws URISyntaxException {
        // given:
        String illegalBucketName = "gs://n/puh";
        try {
            // when:
            GsHelper.toBlobId(new URI(illegalBucketName));

            fail("An exception is expected");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(illegalBucketName));
        }
    }

    @Test
    public void toUri() {
        assertEquals("gs://bucket/blob",  GsHelper.toUriString("bucket", "blob"));
        assertEquals("gs://bucket/blob",  GsHelper.toUriString(BlobId.of("bucket", "blob")));
        assertEquals("gs://bucket/blob",  GsHelper.toUri("bucket", "blob").toString());
    }

    @Test
    public void testRoot() {
        String uriStr = GsHelper.toUriString(BlobId.of("bucket", ""));
        assertEquals("gs://bucket/", uriStr);

        URI uri = GsHelper.toUri("bucket", "");
        assertEquals("gs://bucket/",  uri.toString());

        BlobId blobId = GsHelper.toBlobId(uri);
        assertEquals("bucket",  blobId.getBucket());
        assertEquals("",  blobId.getName());
    }

}