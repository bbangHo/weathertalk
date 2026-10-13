package kr.weathertalk.community.internal.image;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import kr.weathertalk.community.internal.config.CommunityStorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * S3 표준 API 어댑터 (결정 P6 — SeaweedFS, 설정만 바꾸면 AWS S3·R2). path-style 주소만 쓴다(SeaweedFS는 가상 호스트 방식을 쓰지 않음).
 * <p>
 * 클라이언트를 둘 두는 이유: 백엔드는 내부 주소(community.storage.endpoint)로 접근하고, 앱에 주는 사전 서명 주소는 공개 기준 주소로
 * 서명해야 한다(서명에 호스트가 들어가므로 주소를 나중에 바꿔 쓸 수 없다). 서명 시각은 저장소가 검증하므로 주입 Clock이 아닌 실제 시각이다.
 * <p>
 * 체크섬은 '필요할 때만'으로 둔다: SDK 기본값(모든 요청에 CRC 체크섬)은 S3 호환 저장소마다 지원이 달라 실패할 수 있다.
 */
@Component
class S3ImageStorage implements ImageStorage, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(S3ImageStorage.class);

	// 저장소 장애가 요청 스레드를 오래 붙잡지 않게 한다(사진은 장 단위로 다시 시도할 수 있다).
	private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(30);

	private final CommunityStorageProperties properties;
	private final S3Client client;
	private final S3Presigner presigner;
	private volatile boolean bucketReady;

	S3ImageStorage(CommunityStorageProperties properties) {
		this.properties = properties;
		if (!properties.configured()) {
			log.warn("사진 저장소(community.storage)가 설정되지 않았습니다. 사진 업로드는 사용할 수 없습니다(S3_* 환경 변수).");
			this.client = null;
			this.presigner = null;
			return;
		}
		var credentials = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
		Region region = Region.of(properties.region());
		this.client = S3Client.builder()
			.endpointOverride(URI.create(properties.endpoint()))
			.region(region)
			.credentialsProvider(credentials)
			.forcePathStyle(true)
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(API_CALL_TIMEOUT).build())
			.build();
		this.presigner = S3Presigner.builder()
			.endpointOverride(URI.create(properties.effectivePublicEndpoint()))
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	@Override
	public boolean configured() {
		return client != null;
	}

	@Override
	public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
		requireConfigured();
		ensureBucket();
		PresignedPutObjectRequest presigned = presigner.presignPutObject(r -> r.signatureDuration(ttl)
			.putObjectRequest(PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(key)
				.contentType(contentType)
				.contentLength(contentLength)
				.build()));
		Map<String, String> headers = new LinkedHashMap<>();
		presigned.signedHeaders().forEach((name, values) -> {
			if (!"host".equalsIgnoreCase(name)) {
				headers.put(name, String.join(",", values));
			}
		});
		return new PresignedUpload(presigned.url().toString(), headers, presigned.expiration());
	}

	@Override
	public String presignView(String key, Duration ttl) {
		requireConfigured();
		return presigner
			.presignGetObject(r -> r.signatureDuration(ttl)
				.getObjectRequest(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build()))
			.url()
			.toString();
	}

	@Override
	public Optional<Long> size(String key) {
		requireConfigured();
		try {
			return Optional.of(client.headObject(r -> r.bucket(properties.bucket()).key(key)).contentLength());
		}
		catch (NoSuchKeyException e) {
			return Optional.empty();
		}
		catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw unavailable(e);
		}
		catch (SdkException e) {
			throw unavailable(e);
		}
	}

	@Override
	public Optional<byte[]> read(String key) {
		requireConfigured();
		try {
			return Optional.of(client.getObjectAsBytes(r -> r.bucket(properties.bucket()).key(key)).asByteArray());
		}
		catch (NoSuchKeyException e) {
			return Optional.empty();
		}
		catch (SdkException e) {
			throw unavailable(e);
		}
	}

	@Override
	public void write(String key, byte[] content, String contentType) {
		requireConfigured();
		ensureBucket();
		try {
			client.putObject(r -> r.bucket(properties.bucket()).key(key).contentType(contentType),
					RequestBody.fromBytes(content));
		}
		catch (SdkException e) {
			throw unavailable(e);
		}
	}

	@Override
	public void delete(String key) {
		requireConfigured();
		try {
			client.deleteObject(r -> r.bucket(properties.bucket()).key(key));
		}
		catch (NoSuchKeyException | NoSuchBucketException e) {
			// 이미 없다
		}
		catch (SdkException e) {
			throw unavailable(e);
		}
	}

	/** 버킷이 없으면 만든다. 성공한 뒤로는 다시 확인하지 않는다(버킷은 지우지 않는다). */
	void ensureBucket() {
		if (bucketReady) {
			return;
		}
		try {
			client.headBucket(r -> r.bucket(properties.bucket()));
		}
		catch (NoSuchBucketException e) {
			createBucket();
		}
		catch (S3Exception e) {
			if (e.statusCode() != 404) {
				throw unavailable(e);
			}
			createBucket();
		}
		catch (SdkException e) {
			throw unavailable(e);
		}
		bucketReady = true;
	}

	private void createBucket() {
		try {
			client.createBucket(r -> r.bucket(properties.bucket()));
			log.info("사진 버킷을 만들었습니다: {}", properties.bucket());
		}
		catch (BucketAlreadyOwnedByYouException e) {
			// 다른 인스턴스가 먼저 만들었다
		}
		catch (SdkException e) {
			throw unavailable(e);
		}
	}

	private void requireConfigured() {
		if (client == null) {
			throw new StorageUnavailableException("사진 저장소가 설정되지 않았습니다.", null);
		}
	}

	private static StorageUnavailableException unavailable(Exception e) {
		return new StorageUnavailableException("사진 저장소 호출에 실패했습니다: " + e.getClass().getSimpleName(), e);
	}

	@Override
	public void destroy() {
		if (client != null) {
			client.close();
			presigner.close();
		}
	}

}
