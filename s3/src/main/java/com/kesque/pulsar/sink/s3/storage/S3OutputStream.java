package com.kesque.pulsar.sink.s3.storage;

import com.amazonaws.AmazonClientException;
import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.SSEAlgorithm;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.amazonaws.services.s3.model.SSECustomerKey;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.kesque.pulsar.sink.s3.AWSS3Config;

import org.apache.parquet.Strings;
// import io.confluent.connect.s3.S3SinkConnectorConfig;
// import io.confluent.connect.storage.common.util.StringUtils;
// import org.apache.kafka.connect.errors.DataException;
import org.apache.parquet.io.PositionOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Output stream enabling multi-part uploads of Kafka records.
 *
 * <p>The implementation has borrowed the general structure of Hadoop's implementation.
 */
public class S3OutputStream extends PositionOutputStream {
  private static final Logger log = LoggerFactory.getLogger(S3OutputStream.class);
  private final AmazonS3 s3;
  private final AWSS3Config connectorConfig;
  private final String bucket;
  private final String key;
  private final String ssea;
  private final SSECustomerKey sseCustomerKey;
  private final String sseKmsKeyId;
  private final ProgressListener progressListener;
  private final int partSize;
  private final CannedAccessControlList cannedAcl;
  private boolean closed;
  private ByteBuffer buffer;
  private MultipartUpload multiPartUpload;
  private final CompressionType compressionType;
  private final int compressionLevel;
  private volatile OutputStream compressionFilter;
  private Long position;
  private long DEFAULT_5MB_PART_SIZE = 5 * 1024 * 1024;


  public S3OutputStream(String key, AWSS3Config conf, AmazonS3 s3) {
    this.s3 = s3;
    this.connectorConfig = conf;
    this.bucket = conf.getBucketName();
    this.key = key;
    this.ssea = ""; //conf.getSsea();
    //final String sseCustomerKeyConfig = conf.getSseCustomerKey();
    /*this.sseCustomerKey = (SSEAlgorithm.AES256.toString().equalsIgnoreCase(ssea)
        && StringUtils.isNotBlank(sseCustomerKeyConfig))
      ? new SSECustomerKey(sseCustomerKeyConfig) : null;
      */
    this.sseKmsKeyId = ""; // conf.getSseKmsKeyId();
    this.sseCustomerKey = null;
    this.partSize = (int)(conf.partSize > DEFAULT_5MB_PART_SIZE ? conf.partSize : DEFAULT_5MB_PART_SIZE);
    this.cannedAcl = CannedAccessControlList.BucketOwnerFullControl; // default is private or get from conf.getCannedAcl();
    this.closed = false;
    this.buffer = ByteBuffer.allocate(this.partSize);
    this.progressListener = new ConnectProgressListener();
    this.multiPartUpload = null;
    this.compressionType = conf.compressionType;
    this.compressionLevel = conf.compressionLevel;
    this.position = 0L;
    log.info("Create S3OutputStream for bucket '{}' key '{}'", bucket, key);
    System.out.println("created S3OutputStream ... buffer partSize " + conf.partSize+ " bucket " + bucket + " keyname: " + key);
  }

  @Override
  public void write(int b) throws IOException {
    System.out.println("write 1 called size b " + b);
    buffer.put((byte) b);
    if (!buffer.hasRemaining()) {
      uploadPart();
    }
    position++;
  }
    /*@Override
    public void write(byte[] b) throws IOException {

        System.out.println("s3outputstream write() " + buffer.hasRemaining() + " remaining bytes " + buffer.remaining());
        write(b, 0, b.length);
    }*/

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    System.out.println("write 3 called off:"  + off + " length:" + len + " remaining:" + buffer.remaining());
    if (b == null) {
      throw new NullPointerException();
    } else if (outOfRange(off, b.length) || len < 0 || outOfRange(off + len, b.length)) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return;
    }

    if (buffer.remaining() <= len) {
      int firstPart = buffer.remaining();
      //System.out.println("first part " + firstPart + " position " + position);
      buffer.put(b, off, firstPart);
      position += firstPart;
      uploadPart();
      write(b, off + firstPart, len - firstPart);
    } else {
      //System.out.println("position " + position + " off " + off + " length " + len);
      buffer.put(b, off, len);
      position += len;
    }
  }

  private static boolean outOfRange(int off, int len) {
    return off < 0 || off > len;
  }

  private void uploadPart() throws IOException {
    System.out.println("upload part partsize is " + partSize);
    uploadPart(partSize);
    buffer.clear();
  }

  private void uploadPart(final int size) throws IOException {
    if (multiPartUpload == null) {
      log.info("New multi-part upload for bucket '{}' key '{}'", bucket, key);
      System.out.println("bucket " + bucket + " key "+ key);
      multiPartUpload = newMultipartUpload();
    }

    System.out.println("OOOOOOOOOOOOOOOOOOOOOOOOOObject upload started");

    try {
      multiPartUpload.uploadPart(new ByteArrayInputStream(buffer.array()), size);
    } catch (Exception e) {
      if (multiPartUpload != null) {
        multiPartUpload.abort();
        log.debug("Multipart upload aborted for bucket '{}' key '{}'.", bucket, key);
      }
      throw new IOException("Part upload failed: ", e.getCause());
    }
  }

  public void commit() throws IOException {
    if (closed) {
      log.warn(
          "Tried to commit data for bucket '{}' key '{}' on a closed stream. Ignoring.",
          bucket,
          key
      );
      return;
    }

    try {
      compressionType.finalize(compressionFilter);
      if (buffer.hasRemaining()) {
        uploadPart(buffer.position());
      }
      multiPartUpload.complete();
      log.debug("Upload complete for bucket '{}' key '{}'", bucket, key);
      System.out.println("Upload complete for bucket " + bucket + " key " + key);
    } catch (Exception e) {
      log.error("Multipart upload failed to complete for bucket '{}' key '{}'", bucket, key);
      throw e;
    } finally {
      buffer.clear();
      multiPartUpload = null;
      internalClose();
    }
  }

  @Override
  public void close() throws IOException {
    internalClose();
  }

  private void internalClose() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    if (multiPartUpload != null) {
      multiPartUpload.abort();
      log.debug("Multipart upload aborted for bucket '{}' key '{}'.", bucket, key);
    }
    super.close();
  }

  private ObjectMetadata newObjectMetadata() {
    ObjectMetadata meta = new ObjectMetadata();
    // if (Strings.isNullOrEmpty(ssea)) {
    //    meta.setSSEAlgorithm(ssea);
    // }
    return meta;
  }

  private MultipartUpload newMultipartUpload() throws IOException {
    InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(
        bucket,
        key,
        newObjectMetadata()
    ).withCannedACL(cannedAcl);

    /*if (SSEAlgorithm.KMS.toString().equalsIgnoreCase(ssea)
        && Strings.isNullOrEmpty(sseKmsKeyId)) {
      log.info("Using KMS Key ID: {}", sseKmsKeyId);
      initRequest.setSSEAwsKeyManagementParams(new SSEAwsKeyManagementParams(sseKmsKeyId));
    } else if (sseCustomerKey != null) {
      log.info("Using KMS Customer Key");
      initRequest.setSSECustomerKey(sseCustomerKey);
    }
    */

    try {
      return new MultipartUpload(s3.initiateMultipartUpload(initRequest).getUploadId());
    } catch (AmazonClientException e) {
      // TODO: elaborate on the exception interpretation. If this is an AmazonServiceException,
      // there's more info to be extracted.
      throw new IOException("Unable to initiate MultipartUpload: " + e, e);
    }
  }

  private class MultipartUpload {
    private final String uploadId;
    private final List<PartETag> partETags;

    public MultipartUpload(String uploadId) {
      this.uploadId = uploadId;
      this.partETags = new ArrayList<>();
      System.out.println("create MultipartUpload " + key + " uploadID " + uploadId);
      log.debug(
          "Initiated multi-part upload for bucket key '{}' with id '{}'",
          key,
          uploadId
      );
    }

    public void uploadPart(ByteArrayInputStream inputStream, int partSize) {
      int currentPartNumber = partETags.size() + 1;
      UploadPartRequest request = new UploadPartRequest()
                                            .withBucketName(bucket)
                                            .withKey(key)
                                            .withUploadId(uploadId)
                                            // .withSSECustomerKey(sseCustomerKey)
                                            .withInputStream(inputStream)
                                            .withPartNumber(currentPartNumber)
                                            .withPartSize(partSize)
                                            .withGeneralProgressListener(progressListener);
      log.debug("Uploading part {} for id '{}'", currentPartNumber, uploadId);
      System.out.println("Uploading part "+ currentPartNumber+" upload id is " +uploadId);
      partETags.add(s3.uploadPart(request).getPartETag());
    }

    public void complete() {
      log.debug("Completing multi-part upload for key '{}', id '{}'", key, uploadId);
      CompleteMultipartUploadRequest completeRequest =
          new CompleteMultipartUploadRequest(bucket, key, uploadId, partETags);
      s3.completeMultipartUpload(completeRequest);
    }

    public void abort() {
      log.warn("Aborting multi-part upload with id '{}'", uploadId);
      try {
        s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId));
      } catch (Exception e) {
        // ignoring failure on abort.
        log.warn("Unable to abort multipart upload, you may need to purge uploaded parts: ", e);
      }
    }
  }

  public OutputStream wrapForCompression() {
    if (compressionFilter == null) {
      // Initialize compressionFilter the first time this method is called.
      compressionFilter = compressionType.wrapForOutput(this, compressionLevel);
    }
    return compressionFilter;
  }

  // Dummy listener for now, just logs the event progress.
  private static class ConnectProgressListener implements ProgressListener {
    public void progressChanged(ProgressEvent progressEvent) {
      log.debug("Progress event: " + progressEvent);
    }
  }

  public long getPos() {
    return position;
  }
}