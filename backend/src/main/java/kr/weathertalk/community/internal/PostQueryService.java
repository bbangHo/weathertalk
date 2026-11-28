package kr.weathertalk.community.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.weathertalk.community.CommunityApi;
import kr.weathertalk.community.PostPage;
import kr.weathertalk.community.PostSummary;
import kr.weathertalk.community.internal.CommentRepository.CommentRow;
import kr.weathertalk.community.internal.PostImageRepository.ImageRow;
import kr.weathertalk.community.internal.PostRepository.PostRow;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.FeelingRecordView;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.MemberStatus;
import kr.weathertalk.member.Requester;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 게시글 조회: 피드(FR-COM-05), 상세(FR-COM-06), 댓글 목록(FR-COM-10), 인기글(FR-COM-12), 내가 쓴 글(FR-COM-16).
 * 비회원도 볼 수 있고 동네 제한이 없다(BR-COM-15).
 * <p>
 * 페이징(BR-COM-08): 요청 개수+1건을 읽어 다음 존재 여부를 정하고 초과분은 버린다. 커서는 읽은 마지막 행에서 만들고,
 * 작성자가 탈퇴한(또는 없는) 글은 그 뒤에 걸러낸다(BR-COM-14) — 그래서 한 페이지가 요청 개수보다 적을 수 있지만 중복·누락은 없다.
 * 작성자 닉네임·민감도·동네명·첨부된 체감 기록은 저장하지 않고 페이지 단위 일괄 조회로 붙인다(BR-COM-18, ADR 0004).
 */
@Service
public class PostQueryService implements CommunityApi {

	/** 게시글 상세: 게시글 항목 + 댓글 첫 페이지(FR-COM-06). */
	public record PostDetail(PostSummary post, CommentPage comments) {
	}

	public record CommentPage(List<CommentView> items, String nextCursor) {
	}

	/**
	 * 댓글 또는 답글. 삭제된 댓글 자리는 {@code deleted=true}이고 작성자·본문이 없다(FR-COM-10).
	 *
	 * @param replies 댓글이면 답글 전부(오래된순), 답글이면 빈 목록
	 * @param mine 조회자 본인 댓글 여부. 비회원이면 null
	 */
	public record CommentView(UUID commentId, boolean deleted, String body, PostSummary.Author author,
			Instant createdAt, boolean edited, Boolean mine, List<CommentView> replies) {
	}

	private final PostRepository posts;
	private final PostImageRepository images;
	private final CommentRepository comments;
	private final PostImageService imageService;
	private final MemberApi memberApi;
	private final NeighborhoodApi neighborhoodApi;
	private final FeelingApi feelingApi;
	private final CommunityProperties properties;
	private final Clock clock;

	PostQueryService(PostRepository posts, PostImageRepository images, CommentRepository comments,
			PostImageService imageService, MemberApi memberApi, NeighborhoodApi neighborhoodApi,
			FeelingApi feelingApi, CommunityProperties properties, Clock clock) {
		this.posts = posts;
		this.images = images;
		this.comments = comments;
		this.imageService = imageService;
		this.memberApi = memberApi;
		this.neighborhoodApi = neighborhoodApi;
		this.feelingApi = feelingApi;
		this.properties = properties;
		this.clock = clock;
	}

	/** 피드 (FR-COM-05). 없는 동네는 NOT_FOUND. */
	public PostPage feed(NeighborhoodId neighborhoodId, Requester viewer, String cursor, Integer size) {
		Neighborhood neighborhood = neighborhoodApi.findNeighborhood(neighborhoodId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "없는 동네입니다."));
		PageCursor after = PageCursor.decode(cursor);
		int limit = pageSize(size);
		List<PostRow> rows = posts.findNeighborhoodPage(neighborhood.id().value(), after, limit + 1);
		return page(rows, limit, viewer);
	}

	@Override
	public PostPage findMemberPosts(MemberId memberId, String cursor, Integer size) {
		PageCursor after = PageCursor.decode(cursor);
		int limit = pageSize(size);
		List<PostRow> rows = posts.findAuthorPage(memberId.value(), after, limit + 1);
		return page(rows, limit, new Requester.Member(memberId, false, false));
	}

	@Override
	public List<PostSummary> findPopularPosts(NeighborhoodId neighborhoodId, Requester requester) {
		Instant since = clock.instant().minus(properties.popular().window());
		List<PostRow> candidates = posts.findPopularCandidates(neighborhoodId.value(), since,
				properties.popular().minLikes());
		Map<MemberId, MemberProfile> authors = activeProfiles(
				candidates.stream().map(PostRow::authorId).collect(java.util.stream.Collectors.toSet()));
		List<PostRow> top = candidates.stream()
			.filter(r -> authors.containsKey(new MemberId(r.authorId())))
			.limit(properties.popular().size())
			.toList();
		return assemble(top, requester);
	}

	/** 게시글 상세 (FR-COM-06). 삭제됐거나 작성자가 탈퇴한 글은 NOT_FOUND. */
	public PostDetail detail(UUID postId, Requester viewer) {
		PostRow row = posts.findById(postId).orElseThrow(PostQueryService::postNotFound);
		List<PostSummary> assembled = assemble(List.of(row), viewer);
		if (assembled.isEmpty()) {
			throw postNotFound();
		}
		return new PostDetail(assembled.getFirst(), comments(postId, viewer, null));
	}

	/**
	 * 댓글 목록 (FR-COM-10): 댓글은 오래된순 커서 페이징(community.comment.page-size), 각 댓글 아래 답글은 전부 오래된순.
	 * 작성자가 탈퇴한 댓글은 삭제된 댓글처럼(답글이 없으면 빼고), 탈퇴한 작성자의 답글은 뺀다(BR-COM-14).
	 */
	public CommentPage comments(UUID postId, Requester viewer, String cursor) {
		if (posts.findById(postId).isEmpty()) {
			throw postNotFound();
		}
		PageCursor after = PageCursor.decode(cursor);
		int limit = properties.comment().pageSize();
		List<CommentRow> rows = comments.findTopLevelPage(postId, after, limit + 1);
		boolean hasNext = rows.size() > limit;
		List<CommentRow> top = hasNext ? rows.subList(0, limit) : rows;
		Map<UUID, List<CommentRow>> replies = new LinkedHashMap<>();
		comments.findReplies(top.stream().map(CommentRow::id).toList())
			.forEach(r -> replies.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r));
		Set<UUID> authorIds = new HashSet<>();
		top.forEach(c -> addIfPresent(authorIds, c.authorId()));
		replies.values().forEach(list -> list.forEach(r -> addIfPresent(authorIds, r.authorId())));
		Map<MemberId, MemberProfile> profiles = activeProfiles(authorIds);
		UUID viewerId = viewer instanceof Requester.Member m ? m.memberId().value() : null;

		List<CommentView> items = new ArrayList<>();
		for (CommentRow c : top) {
			List<CommentView> replyViews = replies.getOrDefault(c.id(), List.of())
				.stream()
				.filter(r -> profiles.containsKey(new MemberId(r.authorId())))
				.map(r -> view(r, profiles, viewer, viewerId, List.of()))
				.toList();
			boolean authorGone = c.deleted() || !profiles.containsKey(new MemberId(c.authorId()));
			if (authorGone && replyViews.isEmpty()) {
				continue;
			}
			items.add(authorGone ? new CommentView(c.id(), true, null, null, c.createdAt(), false, null, replyViews)
					: view(c, profiles, viewer, viewerId, replyViews));
		}
		String next = hasNext ? new PageCursor(top.getLast().createdAt(), top.getLast().id()).encode() : null;
		return new CommentPage(items, next);
	}

	private CommentView view(CommentRow c, Map<MemberId, MemberProfile> profiles, Requester viewer, UUID viewerId,
			List<CommentView> replies) {
		MemberProfile profile = profiles.get(new MemberId(c.authorId()));
		return new CommentView(c.id(), false, c.body(),
				new PostSummary.Author(profile.nickname(), profile.temperatureSensitivity()), c.createdAt(),
				c.updatedAt() != null, viewer instanceof Requester.Member ? c.authorId().equals(viewerId) : null, replies);
	}

	private PostPage page(List<PostRow> rows, int limit, Requester viewer) {
		boolean hasNext = rows.size() > limit;
		List<PostRow> pageRows = hasNext ? rows.subList(0, limit) : rows;
		String next = hasNext ? new PageCursor(pageRows.getLast().createdAt(), pageRows.getLast().id()).encode() : null;
		return new PostPage(assemble(pageRows, viewer), next);
	}

	/** 행 → 항목. 작성자가 탈퇴했거나 없는 글은 뺀다(BR-COM-14). 순서는 유지한다. */
	List<PostSummary> assemble(List<PostRow> rows, Requester viewer) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<MemberId, MemberProfile> profiles = activeProfiles(
				rows.stream().map(PostRow::authorId).collect(java.util.stream.Collectors.toSet()));
		List<PostRow> visible = rows.stream().filter(r -> profiles.containsKey(new MemberId(r.authorId()))).toList();
		if (visible.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = visible.stream().map(PostRow::id).toList();
		Map<NeighborhoodId, Neighborhood> neighborhoods = neighborhoodApi.findNeighborhoods(
				visible.stream().map(r -> new NeighborhoodId(r.neighborhoodCode())).distinct().toList());
		Map<FeelingRecordId, FeelingRecordView> feelings = feelingRecords(visible);
		Map<UUID, List<ImageRow>> attached = images.findAttached(ids);
		Map<UUID, Integer> likeCounts = posts.likeCounts(ids);
		Map<UUID, Integer> commentCounts = posts.commentCounts(ids);
		Requester.Member member = viewer instanceof Requester.Member m ? m : null;
		Set<UUID> liked = member == null ? Set.of() : posts.likedBy(member.memberId().value(), ids);

		List<PostSummary> result = new ArrayList<>();
		for (PostRow r : visible) {
			MemberProfile author = profiles.get(new MemberId(r.authorId()));
			Neighborhood nbr = neighborhoods.get(new NeighborhoodId(r.neighborhoodCode()));
			FeelingRecordView feeling = r.feelingRecordId() == null ? null
					: feelings.get(new FeelingRecordId(r.feelingRecordId()));
			result.add(new PostSummary(r.id(), r.body(), images(attached.getOrDefault(r.id(), List.of())),
					new PostSummary.Neighborhood(r.neighborhoodCode(), nbr == null ? null : nbr.eupmyeondongName(),
							nbr == null ? null : nbr.shortName()),
					feeling == null ? null
							: new PostSummary.AttachedFeelingRecord(feeling.id().value(), feeling.temperatureTag(),
									feeling.humidityTag(), feeling.windTag(), feeling.recordedAt(),
									feeling.temperatureSensitivity()),
					r.createdAt(), r.updatedAt() != null, r.updatedAt(), likeCounts.getOrDefault(r.id(), 0),
					commentCounts.getOrDefault(r.id(), 0),
					new PostSummary.Author(author.nickname(), author.temperatureSensitivity()),
					member == null ? null : liked.contains(r.id()),
					member == null ? null : r.authorId().equals(member.memberId().value())));
		}
		return result;
	}

	/** 첨부된 체감 기록 일괄 조회. 조회할 수 없으면(없음·오류) 첨부 없이 보여준다(FR-COM-05 규칙 6). */
	private Map<FeelingRecordId, FeelingRecordView> feelingRecords(List<PostRow> rows) {
		List<FeelingRecordId> ids = rows.stream()
			.map(PostRow::feelingRecordId)
			.filter(java.util.Objects::nonNull)
			.distinct()
			.map(FeelingRecordId::new)
			.toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		try {
			return feelingApi.findRecords(ids);
		}
		catch (RuntimeException e) {
			return Map.of();
		}
	}

	private List<PostSummary.Image> images(List<ImageRow> rows) {
		return rows.stream()
			.map(i -> new PostSummary.Image(i.id(), imageService.viewUrl(i.objectKey()), i.width(), i.height()))
			.toList();
	}

	/** 익명으로 남긴 글·댓글의 작성자 표시(민감도 없음). */
	private static final MemberProfile ANONYMOUS_PROFILE = new MemberProfile(
			new MemberId(CommunityMemberWithdrawalCleaner.ANONYMOUS_AUTHOR), "탈퇴한 이웃", null, MemberStatus.ACTIVE);

	/** 활동 중인 작성자의 프로필만. 탈퇴·없는 회원은 빠진다(BR-COM-14). 익명으로 남긴 작성자는 "탈퇴한 이웃"으로 보인다. */
	private Map<MemberId, MemberProfile> activeProfiles(Collection<UUID> memberIds) {
		if (memberIds.isEmpty()) {
			return Map.of();
		}
		Map<MemberId, MemberProfile> result = new LinkedHashMap<>();
		if (memberIds.contains(CommunityMemberWithdrawalCleaner.ANONYMOUS_AUTHOR)) {
			result.put(ANONYMOUS_PROFILE.memberId(), ANONYMOUS_PROFILE);
		}
		memberApi.findProfiles(memberIds.stream().map(MemberId::new).toList()).forEach((id, profile) -> {
			if (!profile.withdrawn()) {
				result.put(id, profile);
			}
		});
		return result;
	}

	private int pageSize(Integer size) {
		if (size == null) {
			return properties.feed().pageSize();
		}
		if (size < 1) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "개수는 1 이상이어야 합니다.");
		}
		return Math.min(size, properties.feed().maxPageSize());
	}

	private static void addIfPresent(Set<UUID> set, UUID value) {
		if (value != null) {
			set.add(value);
		}
	}

	private static DomainException postNotFound() {
		return new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 게시글입니다.");
	}

}
