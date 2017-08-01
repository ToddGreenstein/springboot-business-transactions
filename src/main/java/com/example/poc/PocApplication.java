package com.example;

import com.couchbase.client.core.BackpressureException;
import com.couchbase.client.core.time.Delay;
import com.couchbase.client.deps.io.netty.channel.ConnectTimeoutException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonArray;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.error.TemporaryFailureException;
import com.couchbase.client.java.query.AsyncN1qlQueryResult;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.query.ParameterizedN1qlQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rx.Observable;
import com.github.javafaker.*;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

import static com.couchbase.client.java.util.retry.RetryBuilder.anyOf;

@SpringBootApplication
@RestController
@RequestMapping("/")
public class PocApplication implements Filter {

	public static void main(String[] args) {
		SpringApplication.run(PocApplication.class, args);
	}

	// CORS enable
	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		HttpServletResponse response = (HttpServletResponse) res;
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
		chain.doFilter(req, res);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void destroy() {
	}

	// Hostname for couchbase cluster, from application.properties
	@Value("${hostname}")
	private String hostname;

	// Bucket for couchbase cluster, from application.properties
	@Value("${bucket}")
	private String bucket;

	// Password for couchbase cluster, from application.properties
	@Value("${password}")
	private String password;

	// Setup a static logger for console output logging.
	private static final Logger LOGGER = LoggerFactory.getLogger(PocApplication.class);

	// Setup the couchbase cluster
	public @Bean
	Cluster cluster() {
		return CouchbaseCluster.create(hostname);
	}

	// Reference to the open bucket
	public @Bean
	Bucket bucket() {
		return cluster().openBucket(bucket, password);
	}

	// Read N entries using rx async
	// --  curl 'http://localhost:8080/readBulk?items=1000' | python -m json.tool
	@RequestMapping(value = "/readBulk", method = RequestMethod.GET)
	public Object readBulk(@RequestParam("items") int items) {
		final List<JsonObject> results = new ArrayList<>();
		Observable
				.range(0, items)
				.flatMap((Integer id) -> {
					String key1 = "TEST" + id;
					return bucket().async().get(JsonDocument.create(key1))
							.retryWhen(anyOf(BackpressureException.class)
									.max(10)
									.delay(Delay.exponential(TimeUnit.MILLISECONDS, 10, 1000))
									.doOnRetry((Integer integer, Throwable throwable, Long aLong, TimeUnit timeUnit) -> {
										LOGGER.warn("Backpressure Exception caught, retrying");
									})
									.build())
							.retryWhen(anyOf(TemporaryFailureException.class)
									.max(10)
									.delay(Delay.exponential(TimeUnit.MILLISECONDS, 10, 1000))
									.build())
							.retryWhen(anyOf(ConnectTimeoutException.class)
									.max(5)
									.delay(Delay.exponential(TimeUnit.MILLISECONDS, 500, 10000))
									.build());
				})
				.map(doc -> results.add(doc.content())).toList().toBlocking().single();
		return results;
	}

	// Generate a business transaction
	// -- curl 'http://localhost:8080/seedEntities'
	@RequestMapping(value="/seedEntities", method=RequestMethod.GET)
	public Object generateBT() {
		Faker faker = new Faker();
		String company=faker.company().name();
		String appName = faker.company().buzzword();
		String tierName = faker.company().industry();
		String account = UUID.randomUUID().toString();
		String tierId = UUID.randomUUID().toString();
		String appId = UUID.randomUUID().toString();

		// Create Application Document
		bucket().insert(JsonDocument.create("APP::" + appId, JsonObject
				.create()
				.put("name", appName)
				.put("id",appId)
				.put("system-tag",JsonObject.create()
						.put("account",account))
				.put("user-tag",JsonObject.create()
						.put("companyName",company))));

		// Create Tier Document
		bucket().insert(JsonDocument.create("TIER::" + tierId, JsonObject
				.create()
				.put("name", tierName)
				.put("id", tierId)
				.put("system-tag",JsonObject.create()
						.put("application",appName)
						.put("account",account))
				.put("user-tag",JsonObject.create()
						.put("companyName",company))));
		// Create 1-100 business transaction documents
		Integer numBusinessTrans = ThreadLocalRandom.current().nextInt(0,101);
		for (int i = 0; i<= numBusinessTrans; i++) {
			buildSegments(ThreadLocalRandom.current().nextInt(2,21),tierId,faker.company().profession());
		}
		return "Seeded Company:'" + company + "' Tier:'" + tierName +
				"' App Name:'" + appName + "' Total Business Transactions:'" + numBusinessTrans +"'";

	}

	// Internal Method, Build the business transaction segments
	//    Adds a business transaction and associated segments.
	public Object buildSegments(Integer depth, String tierId, String btName){
		final String btId = UUID.randomUUID().toString();
		bucket().insert(JsonDocument.create("BT::" + btId, JsonObject
				.create()
				.put("name", btName)
				.put("id", btId)
				.put("origin",tierId)));
		return Observable
				.range(0, depth)
				.flatMap((Integer id) -> {
					final JsonObject segment;
					String key1 = "FLOW::" + btId + "::" + id;
					String date = String.format("%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tL%1$tz", new Date());
					if (id==0) {
						// Beginning of Transaction
						segment = JsonObject.create()
								.put("bt", "BT::"+btId)
								.put("type", "IN")
								.put("tier_id", tierId)
								.put("call_type", "HTTP")
								.put("up", "START")
								.put("down", "BT::" + btId + "::" + id++)
								.put("timestamp", date);
					}
					else if(id==depth) {
						// End of transaction
						segment = JsonObject.create()
								.put("bt", "BT::"+btId)
								.put("type", "OUT")
								.put("tier_id", tierId)
								.put("call_type", "HTTP")
								.put("up", "FLOW::" + btId + "::" + id--)
								.put("down", "END")
								.put("timestamp", date);
					} else {

							// Middle Segment
							segment = JsonObject.create()
									.put("bt","BT::"+btId)
									.put("type","MID")
									.put("tier_id", tierId)
									.put("call_type","HTTP")
									.put("up", "FLOW::" + btId + "::" + id--)
									.put("down","FLOW::" + btId + "::" + id++)
									.put("timestamp",date );
					}
					// Create the transaction document
					return bucket().async().upsert(JsonDocument.create("FLOW::" + btId + "::" + id, segment))
							.retryWhen(anyOf(BackpressureException.class)
									.max(10)
									.delay(Delay.exponential(TimeUnit.MILLISECONDS, 10, 1000))
									.doOnRetry((Integer integer, Throwable throwable, Long aLong, TimeUnit timeUnit) -> {
										LOGGER.warn("Backpressure Exception caught, retrying");
									})
									.build())
							.retryWhen(anyOf(TemporaryFailureException.class)
									.max(10)
									.delay(Delay.exponential(TimeUnit.MILLISECONDS, 10, 1000))
									.build())
							.retryWhen(anyOf(ConnectTimeoutException.class)
									.max(5)
									.delay(Delay.exponential(TimeUnit.MILLISECONDS, 500, 10000))
									.build());
				})
				.count().map((Integer count) -> count + " Items Added Successfully").toBlocking().single();
	}
}

