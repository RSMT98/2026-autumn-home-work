package company.vk.edu.distrib.compute.rsmt98.kv;

import company.vk.edu.distrib.compute.AbstractHttpServiceFactory;
import company.vk.edu.distrib.compute.kv.KVService;
import company.vk.edu.distrib.compute.kv.KVServiceTest;
import company.vk.edu.distrib.compute.kv.ReplicatedKVServiceTest;

@KVServiceTest
@ReplicatedKVServiceTest
public final class KVServiceFactory extends AbstractHttpServiceFactory<KVService> {
    private static final int DEFAULT_THREAD_COUNT = 128;

    @Override
    protected KVService doCreate(int port) {
        return new HttpKVService(port, DEFAULT_THREAD_COUNT);
    }
}
