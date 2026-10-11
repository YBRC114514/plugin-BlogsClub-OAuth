package run.halo.plugin.blogsclub;

/**
 * Thrown when the BlogsClub AppID / AppKey have not been filled in yet.
 *
 * <p>Previously an incomplete configuration surfaced as an opaque {@code JsonParseException} and a
 * bare HTTP 500. Carrying a dedicated type lets the filters turn it into a readable notice page
 * that tells the operator exactly where to fill in the credentials.</p>
 *
 * @since 1.2.0
 */
public class BlogsClubConfigException extends RuntimeException {

    public static final String MISSING_CREDENTIALS =
        "尚未填写 BlogsClub 的 AppID / AppKey，无法发起授权。";

    public BlogsClubConfigException(String message) {
        super(message);
    }

    public BlogsClubConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
