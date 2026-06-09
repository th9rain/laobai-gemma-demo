package com.th9rain.laobai.gemmademo

object LocalMemoryStore {
    val profile = SeniorProfile()

    fun authorizedFormFields(): List<FormField> = listOf(
        FormField("姓名", profile.name, autofilled = true),
        FormField("年龄段", profile.ageBand, autofilled = true),
        FormField("手机号", profile.phoneMasked, autofilled = true),
        FormField("居住区域", profile.area, autofilled = true),
        FormField("紧急联系人", profile.emergencyContact, autofilled = true),
        FormField("报名课程", profile.preferredCourse, autofilled = true),
    )
}
