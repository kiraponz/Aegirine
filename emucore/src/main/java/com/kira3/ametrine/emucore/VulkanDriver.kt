package com.kira3.ametrine.emucore

import java.io.File

/**
 * A Vulkan driver pack in adrenotools layout (Mesa Turnip and the like): [libraryName] inside
 * [dir]. [dir] must be in the app's internal storage, where the driver can be loaded.
 */
class VulkanDriver(val dir: File, val libraryName: String)
