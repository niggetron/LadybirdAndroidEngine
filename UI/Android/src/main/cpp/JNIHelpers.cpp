/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "JNIHelpers.h"
#include <AK/Utf16String.h>
#include <AK/Utf16View.h>

namespace Ladybird {

jstring JavaEnvironment::jstring_from_ak_string(String const& str)
{
    auto as_utf16 = AK::Utf16String::from_utf8(str);
    auto code_units = as_utf16.utf16_view().utf16_span();
    return m_env->NewString(reinterpret_cast<jchar const*>(code_units.data()), code_units.size());
}

}
