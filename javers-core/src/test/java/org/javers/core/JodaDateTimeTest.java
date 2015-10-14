package org.javers.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import org.javers.core.diff.Diff;
import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;
import org.junit.Test;

import javax.persistence.Entity;
import javax.persistence.Id;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static com.google.common.collect.Sets.newHashSet;

public class JodaDateTimeTest {

    @Test
    public void classCastException() throws IOException {
        Javers javers = JaversBuilder.javers().build();

        A oldEntity = new A("1",
                            new DateTime(2015, 10, 21, 16, 29, ISOChronology.getInstanceUTC()),
                            " a string");

        A newEntity = new A("1",
                            new DateTime(2015, 10, 21, 16, 29),
                            " a string");

        javers.compare(oldEntity, newEntity);

    }

    @Entity
    public static class A {

        @Id
        private String id;

        private DateTime dateTime;

        private String string;

        @JsonCreator
        public A(@JsonProperty("id") String id,
                 @JsonProperty("dateTime") DateTime dateTime,
                 @JsonProperty("string") String string) {
            this.id = id;
            this.dateTime = dateTime;
            this.string = string;
        }

        public A(A a) {
            this.id = a.id;
            this.dateTime = new DateTime(a.dateTime);
            this.string = a.string;
        }

        public String getId() {
            return id;
        }

        public DateTime getDateTime() {
            return dateTime;
        }

        public String getString() {
            return string;
        }

    }

}
