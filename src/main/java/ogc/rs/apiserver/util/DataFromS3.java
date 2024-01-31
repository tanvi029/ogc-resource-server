package ogc.rs.apiserver.util;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import ogc.rs.apiserver.ApiServerVerticle;
import ogc.rs.apiserver.util.awss3.AWS4SignerBase;
import ogc.rs.apiserver.util.awss3.AWS4SignerForAuthorizationHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class DataFromS3 {

  private static final Logger LOGGER = LogManager.getLogger(DataFromS3.class);
  private static HttpClient client;

  private String s3Url;
  static final String S3_BUCKET = System.getenv("S3_BUCKET");
  static final String S3_REGION = System.getenv("S3_REGION");
  static final String S3_ACCESS_KEY = System.getenv("S3_ACCESS_KEY");
  static final String S3_SECRET_KEY = System.getenv("S3_SECRET_KEY");
  private final Map<String, String> headers;
  private URL url;

  public DataFromS3(Vertx vertx) {
    this.s3Url = "https://" + S3_BUCKET + ".s3." + S3_REGION + ".amazonaws.com" + "/";
    client = vertx.createHttpClient(new HttpClientOptions().setSsl(true));
    this.headers = new HashMap<>();
  }

  public Future<HttpClientResponse> getTileFromS3() {
    Promise <HttpClientResponse> response  = Promise.promise();
    client.request(HttpMethod.GET, url.getDefaultPort(), url.getHost(), url.getPath())
        .compose(req -> {
          headers.forEach(req::putHeader);
          return req.send();
        })
        .compose(res -> {
          if (res.statusCode() == 404) {
            response.fail(new OgcException(404, "Not Found", "Tile not found."));
            return response.future();
          } else if (res.statusCode() == 200) {
            response.complete(res);
            return response.future();
          } else {
            LOGGER.error("Internal Server Error, Something went wrong here.");
            response.fail(new OgcException(500, "Internal Server Error", "Internal Server Error"));
            return response.future();
          }
        });

    return response.future();
  }

  public void setSignatureHeader () {
    headers.put("x-amz-content-sha256", AWS4SignerBase.EMPTY_BODY_SHA256);

    AWS4SignerForAuthorizationHeader signer =
        new AWS4SignerForAuthorizationHeader(url, "GET", "s3", S3_REGION);

    String signedAuthorizationHeader = signer.computeSignature(headers, null, // no query parameters
        AWS4SignerBase.EMPTY_BODY_SHA256, S3_ACCESS_KEY, S3_SECRET_KEY);

    headers.put("Authorization", signedAuthorizationHeader);
  }

  public void setUrlFromString(String strUrl) throws OgcException {
    try {
      this.url = new URL(strUrl);
    } catch (MalformedURLException e) {
      LOGGER.error("Internal Server Error, {}", "Malformed URL");
      throw new OgcException(500, "Internal Server Error", "Internal Server Error");
    }
  }

  public String getFullyQualifiedTileUrlString (String collection, String tileMatrixSetId, String tileMatrixId,
                                                String tileRow, String tileCol) {
    this.s3Url = this.s3Url + tileMatrixSetId + "/" + collection + "/" + tileMatrixId + "/" + tileRow + "/" + tileCol +
        ".png";
    return s3Url;
  }
}
