/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greengagedb.exporter.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentUtilsTest {

    @Test
    void testGetStatusValue() {
        assertEquals(1.0, SegmentUtils.getStatusValue("u"));
        assertEquals(1.0, SegmentUtils.getStatusValue("U"));
        assertEquals(0.0, SegmentUtils.getStatusValue("d"));
        assertEquals(0.0, SegmentUtils.getStatusValue("D"));
        assertEquals(0.0, SegmentUtils.getStatusValue(null));
        assertEquals(0.0, SegmentUtils.getStatusValue("unknown"));
    }

    @Test
    void testGetRoleValue() {
        assertEquals(1.0, SegmentUtils.getRoleValue("p"));
        assertEquals(1.0, SegmentUtils.getRoleValue("P"));
        assertEquals(2.0, SegmentUtils.getRoleValue("m"));
        assertEquals(2.0, SegmentUtils.getRoleValue("M"));
        assertEquals(2.0, SegmentUtils.getRoleValue(null));
        assertEquals(2.0, SegmentUtils.getRoleValue("unknown"));
    }

    @Test
    void testGetModeValue() {
        assertEquals(1.0, SegmentUtils.getModeValue("s"));
        assertEquals(1.0, SegmentUtils.getModeValue("S"));
        assertEquals(2.0, SegmentUtils.getModeValue("r"));
        assertEquals(2.0, SegmentUtils.getModeValue("R"));
        assertEquals(3.0, SegmentUtils.getModeValue("c"));
        assertEquals(3.0, SegmentUtils.getModeValue("C"));
        assertEquals(4.0, SegmentUtils.getModeValue("n"));
        assertEquals(4.0, SegmentUtils.getModeValue("N"));
        assertEquals(4.0, SegmentUtils.getModeValue(null));
    }
}

