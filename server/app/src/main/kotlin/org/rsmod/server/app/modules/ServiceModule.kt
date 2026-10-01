package org.rsmod.server.app.modules

import dev.openrune.studio.http.StudioProjectHttpService
import org.rsmod.module.ExtendedModule
import org.rsmod.server.app.GameService
import org.rsmod.server.services.Service

object ServiceModule : ExtendedModule() {
    override fun bind() {
        addSetBinding<Service>(GameService::class.java)
        addSetBinding<Service>(StudioProjectHttpService::class.java)
    }
}
