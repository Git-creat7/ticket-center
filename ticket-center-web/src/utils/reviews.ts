import type { EventReview } from '../types/api'

export function updateReviewLikeState(review: EventReview) {
  review.isLike = !review.isLike
  review.liked = Math.max(0, review.liked + (review.isLike ? 1 : -1))
}
