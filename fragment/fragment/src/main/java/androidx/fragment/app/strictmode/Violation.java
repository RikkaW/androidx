/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.fragment.app.strictmode;

import androidx.annotation.RestrictTo;

/** Root class for all FragmentStrictMode violations. */
@SuppressWarnings("ExceptionName")
@RestrictTo(RestrictTo.Scope.LIBRARY) // TODO: Make API public as soon as we have a few checks
public abstract class Violation extends RuntimeException {
}