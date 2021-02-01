package ca.uhn.fhir.jpa.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import com.google.common.base.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.r4.model.Measure;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class, properties = {
		"spring.batch.job.enabled=false", "spring.datasource.url=jdbc:h2:mem:dbr4", "hapi.fhir.fhir_version=r4",
		"hapi.fhir.subscription.websocket_enabled=true", "hapi.fhir.empi_enabled=true",
		// Override is currently required when using Empi as the construction of the
		// Empi beans are ambiguous as they are constructed multiple places. This is
		// evident when running in a spring boot environment
		"spring.main.allow-bean-definition-overriding=true" })
public class OperationCollectDataTest {

	private static final String OBS_FILE_PATH = "src/test/resources/ObsBundle.json";

	private static final String MEASURE_FILE_PATH = "src/test/resources/FhirMeasure.json";

	private static final String MEASURE_RESOURCE_ID = "TX-PVLS";

	private static final String USER_NAME = "hapi";

	private static final String USER_PASSWORD = "hapi123";

	protected static CloseableHttpClient ourHttpClient;

	protected static String ourServerBase;

	private IGenericClient ourClient;

	private FhirContext ourCtx;

	@LocalServerPort
	private int port;

	@Test
	public void testCollectDataOperation() throws IOException {
		String paramName1 = "measureReport";
		String paramName2 = "resource";

		// Post the Measure Resource
		Measure measure = readMeasureFromFile();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE_ID).encodedJson().execute();

		// post the obs bundle
		postResource(ourServerBase, OBS_FILE_PATH);

		// fetch parameter result from the operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE_ID
				+ "/$collect-data?periodStart=2021-01-01&periodEnd=2021-01-31");

		assertTrue(result.hasParameter(paramName1));
		assertTrue(result.hasParameter(paramName2));
		assertEquals(5, result.getParameter().size());

		assertTrue(result.getParameter().get(0).getResource() instanceof MeasureReport);
		assertTrue(result.getParameter().get(1).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(2).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(3).getResource() instanceof Patient);
		assertTrue(result.getParameter().get(4).getResource() instanceof Patient);

		// get measure report from the Parameter Result
		MeasureReport report = (MeasureReport) result.getParameter().get(0).getResource();
		assertEquals(report.getEvaluatedResource().size(), 4);
		assertEquals(report.getMeasure(), "Measure/TX_PVLS");
		assertEquals(report.getStatus(), MeasureReport.MeasureReportStatus.COMPLETE);
		assertEquals(report.getType(), MeasureReport.MeasureReportType.DATACOLLECTION);

		// get Observation Bundle from the Parameter Result
		Observation observation1 = (Observation) result.getParameter().get(1).getResource();
		assertEquals(observation1.getCode().getCodingFirstRep().getCode(), "1305AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

		Observation observation2 = (Observation) result.getParameter().get(2).getResource();
		assertEquals(observation2.getCode().getCodingFirstRep().getCode(), "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	}

	@BeforeEach
	void beforeEach() {
		ourServerBase = "http://localhost:" + port + "/fhir";
		ourCtx = FhirContext.forR4();
		ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		ourCtx.setParserErrorHandler(new StrictErrorHandler());

		ourHttpClient = HttpClientBuilder.create().build();
		setHapiClient();
	}

	private Parameters fetchParameter(String theUrl) throws IOException {
		Parameters parameters;
		HttpGet get = new HttpGet(theUrl);

		String auth = USER_NAME + ":" + USER_PASSWORD;
		byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.ISO_8859_1));
		String authHeader = "Basic " + new String(encodedAuth);
		get.addHeader(HttpHeaders.AUTHORIZATION, authHeader);
		get.addHeader(HttpHeaders.ACCEPT, "application/fhir+json");

		try (CloseableHttpResponse resp = ourHttpClient.execute(get)) {
			parameters = ourCtx.newJsonParser().parseResource(Parameters.class,
					EntityUtils.toString(resp.getEntity(), Charsets.UTF_8));
		}

		return parameters;
	}

	private void postResource(String theUrl, String filePath)
			throws IOException {
		HttpPost post = new HttpPost(theUrl);
		String json = readJsonFile(filePath);

		StringEntity entity = new StringEntity(json, StandardCharsets.UTF_8);
		post.setEntity(entity);
		post.setHeader(HttpHeaders.ACCEPT, "application/fhir+json");
		post.setHeader(HttpHeaders.CONTENT_TYPE, "application/fhir+json");

		String auth = USER_NAME + ":" + USER_PASSWORD;
		byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.ISO_8859_1));
		String authHeader = "Basic " + new String(encodedAuth);
		post.addHeader(HttpHeaders.AUTHORIZATION, authHeader);

		post.addHeader(Constants.HEADER_CACHE_CONTROL, Constants.CACHE_CONTROL_NO_CACHE);

		try (CloseableHttpResponse res = ourHttpClient.execute(post)) {
			if (res.getStatusLine().getStatusCode() != 200) {
				throw new IllegalStateException(
						"Attempting to POST resource bundle failed with status code " + res.getStatusLine().getStatusCode()
								+ " " + EntityUtils.toString(res.getEntity(), StandardCharsets.UTF_8));
			}
		}
	}

	private String readJsonFile(String path) throws IOException {
		return FileUtils.readFileToString(new File(path), StandardCharsets.UTF_8);
	}

	private void setHapiClient() {
		ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
		ourClient.registerInterceptor(new LoggingInterceptor(true));
		// Create an HTTP basic auth interceptor
		IClientInterceptor authInterceptor = new BasicAuthInterceptor(USER_NAME, USER_PASSWORD);
		ourClient.registerInterceptor(authInterceptor);
	}

	public Measure readMeasureFromFile() throws IOException {
		String json = readJsonFile(MEASURE_FILE_PATH);

		IParser parser = ourCtx.newJsonParser();
		return parser.parseResource(Measure.class, json);
	}
}