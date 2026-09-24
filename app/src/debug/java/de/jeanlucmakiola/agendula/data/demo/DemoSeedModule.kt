package de.jeanlucmakiola.agendula.data.demo

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.jeanlucmakiola.agendula.data.di.LaunchHook
import javax.inject.Provider

/** Sample data on `am start … --ez agendula_seed true`; debug builds only. */
@Module
@InstallIn(SingletonComponent::class)
object DemoSeedModule {
    @Provides
    @IntoSet
    fun demoSeedHook(seeder: Provider<DemoSeeder>): LaunchHook = LaunchHook { intent ->
        if (intent.getBooleanExtra("agendula_seed", false)) seeder.get().seed()
    }
}
