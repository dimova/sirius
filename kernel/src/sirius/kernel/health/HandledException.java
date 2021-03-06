/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.kernel.health;

/**
 * An exception which has already been handled (logged and reacted upon) can be represented by
 * <tt>HandledException</tt>.
 * <p>
 * Instances of this type need no further treatment and guarantee to have a properly translated error message,
 * which van be directly shown to the user.
 * </p>
 * <p>
 * Creating a <tt>HandledException</tt> is done by using one of the static methods of {@link Exceptions}.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/08
 */
public class HandledException extends RuntimeException {

    /**
     * Creates a new instance with the given message and no exception attached
     *
     * @param message the message to be shown to the user
     */
    protected HandledException(String message) {
        super(message);
    }

    /**
     * Created a new instance with the given message and exception attached
     *
     * @param message the message to be shown to the user
     * @param e       the exception to be attached
     */
    protected HandledException(String message, Throwable e) {
        super(message, e);
    }
}
