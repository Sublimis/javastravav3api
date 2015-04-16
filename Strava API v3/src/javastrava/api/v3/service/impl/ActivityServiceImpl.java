package javastrava.api.v3.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javastrava.api.v3.auth.model.Token;
import javastrava.api.v3.model.StravaActivity;
import javastrava.api.v3.model.StravaActivityUpdate;
import javastrava.api.v3.model.StravaActivityZone;
import javastrava.api.v3.model.StravaAthlete;
import javastrava.api.v3.model.StravaComment;
import javastrava.api.v3.model.StravaLap;
import javastrava.api.v3.model.StravaMap;
import javastrava.api.v3.model.StravaPhoto;
import javastrava.api.v3.model.reference.StravaResourceState;
import javastrava.api.v3.service.ActivityService;
import javastrava.api.v3.service.exception.BadRequestException;
import javastrava.api.v3.service.exception.NotFoundException;
import javastrava.api.v3.service.exception.StravaInternalServerErrorException;
import javastrava.api.v3.service.exception.StravaUnknownAPIException;
import javastrava.api.v3.service.exception.UnauthorizedException;
import javastrava.cache.StravaCacheImpl;
import javastrava.config.Messages;
import javastrava.util.Paging;
import javastrava.util.PagingHandler;
import javastrava.util.PrivacyUtils;
import javastrava.util.StravaDateUtils;

/**
 * @author Dan Shannon
 *
 */
public class ActivityServiceImpl extends StravaServiceImpl implements ActivityService {
	/**
	 * <p>
	 * Returns an instance of {@link ActivityService activity services}
	 * </p>
	 *
	 * <p>
	 * Instances are cached so that if 2 requests are made for the same token,
	 * the same instance is returned
	 * </p>
	 *
	 * @param token
	 *            The Strava access token to be used in requests to the Strava
	 *            API
	 * @return An instance of the activity services
	 */
	public static ActivityService instance(final Token token) {
		// Get the service from the token's cache
		ActivityService service = token.getService(ActivityService.class);

		// If it's not already there, create a new one and put it in the token
		if (service == null) {
			service = new ActivityServiceImpl(token);
			token.addService(ActivityService.class, service);
		}
		return service;
	}

	private final StravaCacheImpl<StravaActivity, Integer> activityCache;
	private final StravaCacheImpl<StravaComment, Integer> commentCache;
	private final StravaCacheImpl<StravaLap, Integer> lapCache;
	private final StravaCacheImpl<StravaMap, String> mapCache;
	private final StravaCacheImpl<StravaPhoto, Integer> photoCache;

	private ActivityServiceImpl(final Token token) {
		super(token);
		this.activityCache = new StravaCacheImpl<StravaActivity, Integer>(StravaActivity.class, token);
		this.commentCache = new StravaCacheImpl<StravaComment, Integer>(StravaComment.class, token);
		this.lapCache = new StravaCacheImpl<StravaLap, Integer>(StravaLap.class, token);
		this.mapCache = new StravaCacheImpl<StravaMap, String>(StravaMap.class, token);
		this.photoCache = new StravaCacheImpl<StravaPhoto, Integer>(StravaPhoto.class, token);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#createComment(java.lang.Integer,
	 *      java.lang.String)
	 */
	@Override
	public StravaComment createComment(final Integer activityId, final String text) throws NotFoundException,
	BadRequestException {
		if ((text == null) || text.equals("")) { //$NON-NLS-1$
			throw new IllegalArgumentException(Messages.string("ActivityServiceImpl.commentCannotBeEmpty")); //$NON-NLS-1$
		}

		// TODO This is a workaround for issue #30 - API allows comments to be posted without write access
		// Token must have write access
		if (!(getToken().hasWriteAccess())) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.commentWithoutWriteAccess")); //$NON-NLS-1$
		}
		// End of workaround

		// Activity must exist
		final StravaActivity activity = getActivity(activityId);
		if (activity == null) {
			throw new NotFoundException(Messages.string("ActivityServiceImpl.commentOnInvalidActivity")); //$NON-NLS-1$
		}

		// If activity is private and not accessible, cannot be commented on
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.commentOnPrivateActivity")); //$NON-NLS-1$
		}

		// Create the comment
		return this.api.createComment(activityId, text);

	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#createManualActivity(javastrava.api.v3.model.StravaActivity)
	 */
	@Override
	public StravaActivity createManualActivity(final StravaActivity activity) {
		// Token must have write access
		if (!getToken().hasWriteAccess()) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.createActivityWithoutWriteAccess")); //$NON-NLS-1$
		}

		// Token must have view_private to write a private activity
		if ((activity.getPrivateActivity() != null) && activity.getPrivateActivity().equals(Boolean.TRUE)
				&& !getToken().hasViewPrivate()) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.createPrivateActivityWithoutViewPrivate")); //$NON-NLS-1$
		}

		// Create the activity
		try {
			return this.api.createManualActivity(activity);
		} catch (final BadRequestException e) {
			throw new IllegalArgumentException(e);
		}
		// TODO Workaround for issue javastrava-api #49
		// (https://github.com/danshannon/javastravav3api/issues/49)
		catch (final StravaInternalServerErrorException e) {
			throw new IllegalArgumentException(e);
		}
		// End of workaround
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#deleteActivity(java.lang.Integer)
	 */
	@Override
	public StravaActivity deleteActivity(final Integer id) throws NotFoundException {
		// Token must have write access
		if (!getToken().hasWriteAccess()) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.deleteActivityWithoutWriteAccess")); //$NON-NLS-1$
		}

		// Activity must exist
		final StravaActivity activity = getActivity(id);
		if (activity == null) {
			throw new NotFoundException(Messages.string("ActivityServiceImpl.deleteInvalidActivity")); //$NON-NLS-1$
		}

		// To delete a private activity, token must have view_private access
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.deletePrivateActivityWithoutViewPrivate")); //$NON-NLS-1$
		}

		try {
			return this.api.deleteActivity(id);
		} catch (final NotFoundException e) {
			return null;
		}
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#deleteComment(java.lang.Integer,
	 *      java.lang.Integer)
	 */
	@Override
	public void deleteComment(final Integer activityId, final Integer commentId) throws NotFoundException {
		// TODO This is a workaround for issue #63 (can delete comments without write access)
		// Token must have write access
		if (!(getToken().hasWriteAccess())) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.deleteCommentWithoutWriteAccess")); //$NON-NLS-1$
		}
		// End of workaround

		// Activity must exist
		final StravaActivity activity = getActivity(activityId);
		if (activity == null) {
			throw new NotFoundException(Messages.string("ActivityServiceImpl.deleteCommentOnInvalidActivity")); //$NON-NLS-1$
		}

		// Token must have view_private to delete a comment on a private
		// activity
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.deleteCommentOnPrivateActivity")); //$NON-NLS-1$
		}

		// End of workaround
		this.api.deleteComment(activityId, commentId);

	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#deleteComment(javastrava.api.v3.model.StravaComment)
	 */
	@Override
	public void deleteComment(final StravaComment comment) throws NotFoundException {
		deleteComment(comment.getActivityId(), comment.getId());
	}

	private StravaActivity doUpdateActivity(final Integer id, final StravaActivityUpdate update) {
		try {
			StravaActivity response = this.api.updateActivity(id, update);
			if (response.getResourceState() == StravaResourceState.UPDATING) {
				response = getActivity(id);
			}
			return response;
		} catch (final NotFoundException e) {
			return null;
		}
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#getActivity(java.lang.Integer)
	 */
	@Override
	public StravaActivity getActivity(final Integer id) {
		return getActivity(id, Boolean.FALSE);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#getActivity(java.lang.Integer,
	 *      java.lang.Boolean)
	 */
	@Override
	public StravaActivity getActivity(final Integer activityId, final Boolean includeAllEfforts) {
		// Attempt to get the activity from cache
		// TODO THIS IS THE BIT THAT NEEDS DOING
		
		StravaActivity stravaResponse = null;

		try {
				stravaResponse = this.api.getActivity(activityId, includeAllEfforts);
		} catch (final NotFoundException e) {
			// Activity doesn't exist - return null
			return null;
		} catch (final UnauthorizedException e) {
			return PrivacyUtils.privateActivity(activityId);
		}
		return stravaResponse;
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#giveKudos(java.lang.Integer)
	 */
	@Override
	public void giveKudos(final Integer activityId) throws NotFoundException {
		// Must have write access to give kudos
		if (!(getToken().hasWriteAccess())) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.kudosWithoutWriteAccess")); //$NON-NLS-1$
		}

		// Activity must exist
		final StravaActivity activity = getActivity(activityId);
		if (activity == null) {
			throw new NotFoundException(Messages.string("ActivityServiceImpl.kudosInvalidActivity")); //$NON-NLS-1$
		}

		// Must have view_private to give kudos to a private activity
		if (!getToken().hasViewPrivate() && (activity.getResourceState() == StravaResourceState.PRIVATE)) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.kudosPrivateActivityWithoutViewPrivate")); //$NON-NLS-1$
		}

		this.api.giveKudos(activityId);

	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityComments(java.lang.Integer)
	 */
	@Override
	public List<StravaComment> listActivityComments(final Integer id) {
		return listActivityComments(id, Boolean.FALSE);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityComments(java.lang.Integer,
	 *      java.lang.Boolean)
	 */
	@Override
	public List<StravaComment> listActivityComments(final Integer id, final Boolean markdown) {
		return listActivityComments(id, markdown, null);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityComments(Integer,
	 *      Boolean, Paging)
	 */
	@Override
	public List<StravaComment> listActivityComments(final Integer id, final Boolean markdown,
			final Paging pagingInstruction) {
		// If the activity doesn't exist, then neither do the comments
		final StravaActivity activity = getActivity(id);
		if (activity == null) { // doesn't exist
			return null;
		}

		// If the activity is private and not accessible, don't return the
		// comments
		if (activity.getResourceState() == StravaResourceState.PRIVATE) { // is
			// private
			return new ArrayList<StravaComment>();
		}

		return PagingHandler.handlePaging(
				pagingInstruction,
				thisPage -> Arrays.asList(ActivityServiceImpl.this.api.listActivityComments(id, markdown,
						thisPage.getPage(), thisPage.getPageSize())));
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityComments(java.lang.Integer,
	 *      javastrava.util.Paging)
	 */
	@Override
	public List<StravaComment> listActivityComments(final Integer id, final Paging pagingInstruction) {
		return listActivityComments(id, Boolean.FALSE, pagingInstruction);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityKudoers(java.lang.Integer)
	 */
	@Override
	public List<StravaAthlete> listActivityKudoers(final Integer id) {
		return listActivityKudoers(id, null);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityKudoers(Integer,
	 *      Paging)
	 */
	@Override
	public List<StravaAthlete> listActivityKudoers(final Integer id, final Paging pagingInstruction) {
		// If the activity doesn't exist, then neither do the kudoers
		final StravaActivity activity = getActivity(id);
		if (activity == null) {
			return null;
		}

		// If the activity is private and inaccessible, return an empty list
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			return new ArrayList<StravaAthlete>();
		}

		return PagingHandler.handlePaging(pagingInstruction, thisPage -> Arrays.asList(ActivityServiceImpl.this.api
				.listActivityKudoers(id, thisPage.getPage(), thisPage.getPageSize())));

	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityLaps(java.lang.Integer)
	 */
	@Override
	public List<StravaLap> listActivityLaps(final Integer id) {
		// If the activity doesn't exist, return null
		final StravaActivity activity = getActivity(id);
		if (activity == null) {
			return null;
		}

		// If the activity is private and inaccessible, return an empty list
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			return new ArrayList<StravaLap>();
		}

		try {
			return Arrays.asList(this.api.listActivityLaps(id));
		} catch (final NotFoundException e) {
			return null;
		}

	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityPhotos(java.lang.Integer)
	 */
	@Override
	public List<StravaPhoto> listActivityPhotos(final Integer id) {
		// If the activity doesn't exist, return null
		final StravaActivity activity = getActivity(id);
		if (activity == null) {
			return null;
		}

		// If the activity is private and inaccessible, return an empty list
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			return new ArrayList<StravaPhoto>();
		}

		try {
			final StravaPhoto[] photos = this.api.listActivityPhotos(id);

			// TODO This fixes an inconsistency with the listActivityComments
			// API (issue #76)
			// call on Strava, which returns an empty array, not null
			if (photos == null) {
				return new ArrayList<StravaPhoto>();
			}

			return Arrays.asList(photos);

		} catch (final NotFoundException e) {
			return null;
		}
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listActivityZones(java.lang.Integer)
	 */
	@Override
	public List<StravaActivityZone> listActivityZones(final Integer id) {
		// If the activity doesn't exist, return null
		final StravaActivity activity = getActivity(id);
		if (activity == null) {
			return null;
		}

		// If the activity is private and inaccesible, return an empty list
		if (activity.getResourceState() == StravaResourceState.PRIVATE) {
			return new ArrayList<StravaActivityZone>();
		}

		try {
			return Arrays.asList(this.api.listActivityZones(id));
		} catch (final NotFoundException e) {
			return null;
		}
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAllActivityComments(java.lang.Integer)
	 */
	@Override
	public List<StravaComment> listAllActivityComments(final Integer activityId) {
		return PagingHandler.handleListAll(thisPage -> listActivityComments(activityId, thisPage));
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAllActivityKudoers(java.lang.Integer)
	 */
	@Override
	public List<StravaAthlete> listAllActivityKudoers(final Integer activityId) {
		return PagingHandler.handleListAll(thisPage -> listActivityKudoers(activityId, thisPage));
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAllAuthenticatedAthleteActivities()
	 */
	@Override
	public List<StravaActivity> listAllAuthenticatedAthleteActivities() {
		return PagingHandler.handleListAll(thisPage -> listAuthenticatedAthleteActivities(thisPage));

	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAllAuthenticatedAthleteActivities(LocalDateTime,
	 *      LocalDateTime)
	 */
	@Override
	public List<StravaActivity> listAllAuthenticatedAthleteActivities(final LocalDateTime before,
			final LocalDateTime after) {
		final List<StravaActivity> activities = PagingHandler
				.handleListAll(thisPage -> listAuthenticatedAthleteActivities(before, after, thisPage));

		return activities;
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAllFriendsActivities()
	 */
	@Override
	public List<StravaActivity> listAllFriendsActivities() {
		return PagingHandler.handleListAll(thisPage -> listFriendsActivities(thisPage));
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAllRelatedActivities(java.lang.Integer)
	 */
	@Override
	public List<StravaActivity> listAllRelatedActivities(final Integer activityId) {
		return PagingHandler.handleListAll(thisPage -> listRelatedActivities(activityId, thisPage));
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAuthenticatedAthleteActivities()
	 */
	@Override
	public List<StravaActivity> listAuthenticatedAthleteActivities() {
		return listAuthenticatedAthleteActivities(null, null, null);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAuthenticatedAthleteActivities(LocalDateTime,
	 *      LocalDateTime)
	 */
	@Override
	public List<StravaActivity> listAuthenticatedAthleteActivities(final LocalDateTime before, final LocalDateTime after) {
		return listAuthenticatedAthleteActivities(before, after, null);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAuthenticatedAthleteActivities(LocalDateTime,
	 *      LocalDateTime, Paging)
	 */
	@Override
	public List<StravaActivity> listAuthenticatedAthleteActivities(final LocalDateTime before,
			final LocalDateTime after, final Paging pagingInstruction) {
		final Integer secondsBefore = StravaDateUtils.secondsSinceUnixEpoch(before);
		final Integer secondsAfter = StravaDateUtils.secondsSinceUnixEpoch(after);

		List<StravaActivity> activities = PagingHandler.handlePaging(pagingInstruction, thisPage -> Arrays
				.asList(this.api.listAuthenticatedAthleteActivities(secondsBefore, secondsAfter, thisPage.getPage(),
						thisPage.getPageSize())));

		activities = PrivacyUtils.handlePrivateActivities(activities, this.getToken());

		return activities;
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listAuthenticatedAthleteActivities(javastrava.util.Paging)
	 */
	@Override
	public List<StravaActivity> listAuthenticatedAthleteActivities(final Paging pagingInstruction) {
		return listAuthenticatedAthleteActivities(null, null, pagingInstruction);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listFriendsActivities()
	 */
	@Override
	public List<StravaActivity> listFriendsActivities() {
		return listFriendsActivities(null);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listFriendsActivities(Paging)
	 */
	@Override
	public List<StravaActivity> listFriendsActivities(final Paging pagingInstruction) {
		final List<StravaActivity> activities = PagingHandler.handlePaging(pagingInstruction,
				thisPage -> Arrays.asList(this.api.listFriendsActivities(thisPage.getPage(), thisPage.getPageSize())));
		return PrivacyUtils.handlePrivateActivities(activities, this.getToken());
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listRelatedActivities(java.lang.Integer)
	 */
	@Override
	public List<StravaActivity> listRelatedActivities(final Integer id) {
		return listRelatedActivities(id, null);
	}

	/**
	 * @see javastrava.api.v3.service.ActivityService#listRelatedActivities(java.lang.Integer,
	 *      javastrava.util.Paging)
	 */
	@Override
	public List<StravaActivity> listRelatedActivities(final Integer id, final Paging pagingInstruction) {
		final List<StravaActivity> activities = PagingHandler.handlePaging(pagingInstruction, thisPage -> Arrays
				.asList(ActivityServiceImpl.this.api.listRelatedActivities(id, thisPage.getPage(),
						thisPage.getPageSize())));
		return PrivacyUtils.handlePrivateActivities(activities, this.getToken());
	}

	/**
	 * @throws NotFoundException
	 *             If the activity with the given id does not exist
	 * @see javastrava.api.v3.service.ActivityService#updateActivity(Integer,javastrava.api.v3.model.StravaActivityUpdate)
	 */
	@Override
	public StravaActivity updateActivity(final Integer id, final StravaActivityUpdate activity)
			throws NotFoundException {
		final StravaActivityUpdate update = activity;
		if (activity == null) {
			return getActivity(id);
		}
		StravaActivity response = null;

		// Activity must exist to be updated
		final StravaActivity stravaActivity = getActivity(id);
		if (stravaActivity == null) {
			throw new NotFoundException(Messages.string("ActivityServiceImpl.updateInvalidActivity")); //$NON-NLS-1$
		}

		// Activity must not be private and inaccessible
		if (stravaActivity.getResourceState() == StravaResourceState.PRIVATE) {
			throw new UnauthorizedException(Messages.string("ActivityServiceImpl.updatePrivateActivity")); //$NON-NLS-1$
		}

		// TODO Workaround for issue javastrava-api #36
		// (https://github.com/danshannon/javastravav3api/issues/36)
		if (update.getCommute() != null) {
			final StravaActivityUpdate commuteUpdate = new StravaActivityUpdate();
			commuteUpdate.setCommute(update.getCommute());
			response = doUpdateActivity(id, commuteUpdate);
			if (response.getCommute() != update.getCommute()) {
				throw new StravaUnknownAPIException(
						Messages.string("ActivityServiceImpl.failedToUpdateCommuteFlag") + id, null, null); //$NON-NLS-1$
			}

			update.setCommute(null);
		}

		// End of workaround

		response = doUpdateActivity(id, update);
		return response;

	}

}
